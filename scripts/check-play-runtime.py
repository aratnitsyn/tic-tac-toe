#!/usr/bin/env python3
"""Real Auth/Gateway/Lobby/Game gameplay smoke with SIGKILL durability checks.

Creates three local accounts and three matches; retains data and stops its processes.
Checks confirmed moves across kill, paused restart, recovery, surrender and turn timeout.
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
    directory = Path(tempfile.mkdtemp(prefix="ttt-play-smoke-"))
    names = ("auth", "game", "lobby", "api-gateway")
    bases = {name: "http://127.0.0.1:" + str(smoke.port()) for name in names}
    processes, logs = [], []
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")

    def start(service):
        env = smoke.local.environment(service)
        env.update({"PORT": bases[service].rsplit(":", 1)[1], "SERVER_ADDRESS": "127.0.0.1",
                    "AUTH_URL": bases["auth"], "GAME_URL": bases["game"], "LOBBY_URL": bases["lobby"],
                    "COORDINATION_RETRY_DELAY_MS": "100", "LOBBY_RETRY_MS": "100"})
        jar = next(p for p in (ROOT / "services" / service / "build/libs").glob("*.jar")
                   if not p.name.endswith("-plain.jar"))
        log = (directory / (service + "-" + str(len(processes)) + ".log")).open("w")
        logs.append(log)
        process = subprocess.Popen([java, "-jar", str(jar)], env=env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        smoke.wait_health(bases[service], process)
        return process

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

    def wait(check, timeout=25):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if check():
                return
            time.sleep(0.1)
        raise AssertionError("Condition not reached; logs: " + str(directory))

    def api(player, path, body=None, key=None):
        headers = {"Authorization": "Bearer " + player["token"], "X-Client-Type": "NATIVE",
                   "X-User-Id": str(uuid.uuid4()), "X-Service-Token": "spoofed"}
        if body is not None:
            headers["Idempotency-Key"] = key or str(uuid.uuid4())
        return exchange(bases["api-gateway"] + "/api/v1/" + path,
                        "POST" if body is not None else "GET", body, headers)

    def data(player, path):
        status, body = api(player, path)
        assert status == 200, (status, body)
        return body["data"]

    def state(player):
        return data(player, "participation")

    def pair(a, b):
        result = api(a, "lobbies", {"expectedVersion": state(a)["version"]})
        assert result[0] == 201, result
        result = api(b, "lobbies/join", {"expectedVersion": state(b)["version"], "code": result[1]["data"]["code"]})
        assert result[0] == 200, result
        identifier = result[1]["data"]["resourceId"]
        for player in (a, b):
            version = data(player, "matches/" + identifier)["version"]
            assert api(player, "matches/" + identifier + "/ready", {"expectedVersion": version})[0] == 200
        wait(lambda: data(a, "matches/" + identifier)["state"] == "RUNNING")
        return identifier

    def observe(player, identifier, connected, generation):
        command = str(uuid.uuid4())
        env = smoke.local.environment("game")
        result = exchange(bases["game"] + "/internal/v1/connections/observe", "POST",
                          {"operationId": command, "userId": player["userId"], "matchId": identifier,
                           "connected": connected, "generation": generation},
                          {"X-Service-Token": env["GAME_SERVICE_TOKEN"], "Idempotency-Key": command})
        assert result[0] == 200, result

    def finish(a, b, path):
        current = data(a, path)
        if current["state"] != "FINISHED":
            assert api(a, path + "/surrender", {"expectedVersion": current["version"]})[0] == 200
        result = data(a, path)
        assert state(a)["state"] == state(b)["state"] == "MENU"
        assert api(b, path + "/surrender", {"expectedVersion": result["version"]})[0] == 409
        assert data(b, path) == result
        return result

    try:
        start("auth")
        game_process = start("game")
        start("lobby")
        start("api-gateway")
        players = []
        for _ in range(3):
            credentials = {"email": "play-" + uuid.uuid4().hex + "@example.com",
                           "password": "Aa1!" + secrets.token_hex(4), "clientType": "NATIVE"}
            headers = {"X-Client-Type": "NATIVE", "Idempotency-Key": str(uuid.uuid4())}
            account = smoke.request(bases["api-gateway"] + "/api/v1/auth/register", "POST", credentials, headers, 201)
            headers["Idempotency-Key"] = str(uuid.uuid4())
            login = smoke.request(bases["api-gateway"] + "/api/v1/auth/login", "POST", credentials, headers)
            players.append({"userId": account["data"]["userId"], "token": login["data"]["token"]})
        a, b, foreign = players
        identifier = pair(a, b)
        path = "games/" + identifier
        initial = data(a, path)
        x = a if initial["xPlayerId"] == a["userId"] else b
        o = b if x is a else a
        assert api(foreign, path)[0] == 403
        assert exchange(bases["api-gateway"] + "/api/v1/" + path)[0] == 401
        assert api(o, path + "/moves", {"expectedVersion": 0, "cell": 0})[0] == 409
        key = str(uuid.uuid4())
        body = {"expectedVersion": 0, "cell": 0}
        accepted = api(x, path + "/moves", body, key)
        assert accepted[0] == 200, accepted
        assert api(x, path + "/moves", body, key) == accepted
        game_process.kill()
        game_process.wait(timeout=10)
        game_process = start("game")
        restored = data(a, path)
        assert restored["board"][0] == "X"
        assert restored["xPlayerId"] == initial["xPlayerId"] and restored["oPlayerId"] == initial["oPlayerId"]
        assert api(x, path + "/moves", body, key) == accepted
        finish(a, b, path)
        # Pause another match, SIGKILL and verify the same recovery deadline and saved turn.
        identifier = pair(a, b)
        path = "games/" + identifier
        observe(a, identifier, False, 0)
        paused = data(b, path)
        assert paused["state"] == "PAUSED"
        assert api(b, path + "/moves", {"expectedVersion": paused["version"], "cell": 0})[0] == 409
        game_process.kill()
        game_process.wait(timeout=10)
        game_process = start("game")
        restored = data(b, path)
        assert restored["state"] == "PAUSED" and restored["remainingTurnMs"] == paused["remainingTurnMs"]
        assert restored["recoveryDeadlines"] == paused["recoveryDeadlines"]
        absent = "X" if restored["xPlayerId"] == a["userId"] else "O"
        assert restored["recoveryRemainingMs"][absent] < paused["recoveryRemainingMs"][absent]
        observe(a, identifier, True, 1)
        resumed = data(b, path)
        assert resumed["state"] == "X_TURN" and resumed["disconnectedPlayers"] == []
        assert resumed["recoveryRemainingMs"][absent] < 30000
        observe(a, identifier, False, 0)
        assert data(b, path)["version"] == resumed["version"]
        result = finish(a, b, path)
        assert result["result"]["reason"] == "SURRENDER"
        # Persisted turn deadline, including background worker, ends without another move.
        identifier = pair(a, b)
        path = "games/" + identifier
        wait(lambda: state(a)["state"] == state(b)["state"] == "MENU")
        result = data(a, path)
        assert result["result"]["reason"] == "TURN_TIMEOUT"
        assert helper.sql("game", "SELECT count(*) FROM outbox WHERE aggregate_id='" + identifier + "' AND event_type='GameFinished'") == "1"
        assert api(a, path + "/moves", {"expectedVersion": result["version"], "cell": 0})[0] == 409
        assert data(b, path) == result
        print("Confirmed move survives SIGKILL; pause budget/deadline survive restart; recovery, fencing, surrender and timer completion passed.")
        print("Logs:", directory)
    finally:
        for process in reversed(processes):
            stop(process)
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
