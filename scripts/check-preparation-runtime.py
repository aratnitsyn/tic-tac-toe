#!/usr/bin/env python3
"""Real Auth/Gateway/Game/Lobby/Matchmaking preparation and cancellation smoke.

Creates six local accounts, cancelled queue/lobby matches and one RUNNING game.
Retains local data and stops only its own processes. Does not test gameplay or WSS.
"""
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("lobby_smoke", ROOT / "scripts/check-lobby-runtime.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
smoke, exchange = helper.smoke, helper.exchange


def main():
    directory = Path(tempfile.mkdtemp(prefix="ttt-preparation-smoke-"))
    names = ("auth", "game", "lobby", "matchmaking", "api-gateway")
    ports = {name: smoke.port() for name in names}
    bases = {name: "http://127.0.0.1:" + str(port) for name, port in ports.items()}
    processes, logs = [], []
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")

    def start(service):
        env = smoke.local.environment(service)
        env.update({"PORT": str(ports[service]), "SERVER_ADDRESS": "127.0.0.1",
                    "AUTH_URL": bases["auth"], "GAME_URL": bases["game"], "LOBBY_URL": bases["lobby"],
                    "MATCHMAKING_URL": bases["matchmaking"], "MATCHMAKING_RETRY_MS": "250",
                    "COORDINATION_RETRY_DELAY_MS": "100", "LOBBY_RETRY_MS": "100"})
        if service == "matchmaking":
            command = [str(ROOT / "services/matchmaking/build/matchmaking")]
        else:
            jar = next(p for p in (ROOT / "services" / service / "build/libs").glob("*.jar")
                       if not p.name.endswith("-plain.jar"))
            command = [java, "-jar", str(jar)]
        log = (directory / (service + "-" + str(len(processes)) + ".log")).open("w")
        logs.append(log)
        process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        if service != "matchmaking":
            smoke.wait_health(bases[service], process)
        else:
            wait(lambda: exchange(bases[service] + "/healthz")[0] == 200)
        return process

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

    def wait(check, timeout=45):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                if check():
                    return
            except OSError:
                pass
            time.sleep(0.1)
        raise AssertionError("Condition not reached; logs: " + str(directory))

    def api(player, path, body=None, key=None):
        headers = {"Authorization": "Bearer " + player["token"], "X-Client-Type": "NATIVE",
                   "X-User-Id": str(uuid.uuid4()), "X-Service-Token": "spoofed"}
        if body is not None:
            headers["Idempotency-Key"] = key or str(uuid.uuid4())
        return exchange(bases["api-gateway"] + "/api/v1/" + path,
                        "POST" if body is not None else "GET", body, headers)

    def state(player):
        status, body = api(player, "participation")
        assert status == 200, (status, body)
        return body["data"]

    def match(player, identifier):
        status, body = api(player, "matches/" + identifier)
        assert status == 200, (status, body)
        return body["data"]

    def ready(player, identifier):
        return api(player, "matches/" + identifier + "/ready",
                   {"expectedVersion": match(player, identifier)["version"]})

    def observe(player, identifier):
        command = str(uuid.uuid4())
        env = smoke.local.environment("game")
        body = {"operationId": command, "userId": player["userId"], "matchId": identifier,
                "generation": 0, "connected": False}
        result = exchange(bases["game"] + "/internal/v1/connections/observe", "POST", body,
                          {"X-Service-Token": env["GAME_SERVICE_TOKEN"], "Idempotency-Key": command})
        assert result[0] == 200, result
        assert exchange(bases["game"] + "/internal/v1/connections/observe", "POST", body,
                        {"X-Service-Token": env["GAME_SERVICE_TOKEN"], "Idempotency-Key": command}) == result

    def lobby_pair(owner, guest):
        result = api(owner, "lobbies", {"expectedVersion": state(owner)["version"]})
        assert result[0] == 201, result
        room = result[1]["data"]
        result = api(guest, "lobbies/join", {"expectedVersion": state(guest)["version"], "code": room["code"]})
        assert result[0] == 200, result
        return room, result[1]["data"]["resourceId"]

    try:
        start("auth")
        game = start("game")
        start("lobby")
        start("matchmaking")
        start("api-gateway")
        players = []
        for _ in range(6):
            credentials = {"email": "preparation-" + uuid.uuid4().hex + "@example.com",
                           "password": "Aa1!" + secrets.token_hex(4), "clientType": "NATIVE"}
            headers = {"X-Client-Type": "NATIVE", "Idempotency-Key": str(uuid.uuid4())}
            account = smoke.request(bases["api-gateway"] + "/api/v1/auth/register", "POST", credentials, headers, 201)
            headers["Idempotency-Key"] = str(uuid.uuid4())
            login = smoke.request(bases["api-gateway"] + "/api/v1/auth/login", "POST", credentials, headers)
            players.append({"userId": account["data"]["userId"], "token": login["data"]["token"]})
        a, b, c, d, e, f = players
        # One accepted queue player returns to the exact ticket and original priority.
        tickets = []
        for player in (a, b):
            result = api(player, "matchmaking/queue", {"expectedVersion": state(player)["version"]})
            assert result[0] == 201, result
            tickets.append(result[1]["data"])
        wait(lambda: state(a)["state"] == "IN_MATCH")
        identifier = state(a)["resourceId"]
        assert state(b)["resourceId"] == identifier
        assert api(c, "matches/" + identifier)[0] == 403
        assert exchange(bases["api-gateway"] + "/api/v1/matches/" + identifier)[0] == 401
        key = str(uuid.uuid4())
        body = {"expectedVersion": match(a, identifier)["version"]}
        accepted = api(a, "matches/" + identifier + "/ready", body, key)
        assert accepted[0] == 200
        assert api(a, "matches/" + identifier + "/ready", body, key) == accepted
        assert api(a, "matches/" + identifier + "/ready", {"expectedVersion": 99}, key)[0] == 409
        wait(lambda: state(a)["state"] == "QUEUED" and state(b)["state"] == "MENU")
        wait(lambda: api(a, "matchmaking/queue")[1]["data"]["status"] == "AVAILABLE")
        returned = api(a, "matchmaking/queue")[1]["data"]
        assert returned["ticketId"] == tickets[0]["ticketId"]
        assert returned["originalSequence"] == tickets[0]["originalSequence"]
        assert api(a, "matchmaking/queue/cancel", {"expectedVersion": state(a)["version"]})[0] == 200
        # Accepting guest becomes the owner of the same room, with a rotated code.
        room, identifier = lobby_pair(c, d)
        assert ready(d, identifier)[0] == 200
        wait(lambda: state(d)["state"] == "LOBBY")
        path = "lobbies/" + room["lobbyId"]
        wait(lambda: api(d, path)[1]["data"]["status"] == "WAITING")
        returned = api(d, path)[1]["data"]
        assert returned["code"] != room["code"]
        # A fresh match starts with no inherited readiness; double close revokes the return.
        result = api(c, "lobbies/join", {"expectedVersion": state(c)["version"], "code": returned["code"]})
        assert result[0] == 200, result
        identifier = result[1]["data"]["resourceId"]
        assert not any(p["ready"] for p in match(c, identifier)["participants"])
        assert ready(c, identifier)[0] == ready(d, identifier)[0] == 200
        observe(c, identifier)
        wait(lambda: state(d)["state"] == "LOBBY")
        # Let the first return reach Lobby before observing the second close.
        wait(lambda: api(d, path)[1]["data"]["status"] == "WAITING")
        observe(d, identifier)
        wait(lambda: state(c)["state"] == state(d)["state"] == "MENU")
        wait(lambda: api(d, path)[1]["data"]["status"] == "CLOSED")
        # Restart Game during countdown; persisted deadline produces one game/start event.
        _, identifier = lobby_pair(e, f)
        assert ready(e, identifier)[0] == ready(f, identifier)[0] == 200
        stop(game)
        game = start("game")
        wait(lambda: match(e, identifier)["state"] == "RUNNING")
        assert helper.sql("game", "SELECT count(*) FROM game WHERE match_id='" + identifier + "' AND state='X_TURN'") == "1"
        assert helper.sql("game", "SELECT count(*) FROM outbox WHERE aggregate_id='" + identifier + "' AND event_type='GameStarted'") == "1"
        print("Preparation ready/replay, real queue return, lobby code rotation, double disconnect and restart/start passed.")
        print("Logs:", directory)
    finally:
        for process in reversed(processes):
            stop(process)
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
