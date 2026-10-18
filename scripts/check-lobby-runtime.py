#!/usr/bin/env python3
"""Verify private Lobby through real services, including races and a dropped Game response.

Creates five local test accounts and up to two WAITING_READY matches; no credentials are printed.
"""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("auth_smoke", ROOT / "scripts/check-auth-runtime.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def sql(service, statement):
    assert service in ("lobby", "game")
    command = ["docker", "compose", "--env-file", str(ROOT / "infra/local/.env"), "-f",
               str(ROOT / "infra/local/compose.yml"), "exec", "-T", "postgres", "bash", "-ec",
               'export PGPASSWORD="$' + service.upper() + '_DB_PASSWORD"; exec psql -X -h 127.0.0.1 -U '
               + service + ' -d ' + service + ' -v ON_ERROR_STOP=1 -At']
    return subprocess.check_output(command, input=statement, text=True).strip()


def exchange(url, method="GET", body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json", **(headers or {})})
    try:
        response = urllib.request.urlopen(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        content = response.read()
        return response.status, json.loads(content) if content else None


def main():
    directory = Path(tempfile.mkdtemp(prefix="ttt-lobby-smoke-"))
    processes, logs = [], []
    ports = {name: smoke.port() for name in ("auth", "api-gateway", "game", "lobby")}
    bases = {name: "http://127.0.0.1:" + str(port) for name, port in ports.items()}
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
    drop = threading.Event()

    class GameProxy(BaseHTTPRequestHandler):
        def forward(self):
            size = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(size) if size else None
            headers = {key: value for key, value in self.headers.items()
                       if key.lower() not in ("host", "content-length", "connection")}
            request = urllib.request.Request(bases["game"] + self.path, data=body, method=self.command, headers=headers)
            try:
                response = urllib.request.urlopen(request, timeout=10)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                payload = response.read()
                if self.command == "POST" and self.path == "/internal/v1/rooms/register" and drop.is_set():
                    drop.clear()
                    self.close_connection = True  # Game committed; Lobby never receives the result.
                    return
                self.send_response(response.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        do_GET = forward
        do_POST = forward

        def log_message(self, *_):
            pass

    proxy = ThreadingHTTPServer(("127.0.0.1", 0), GameProxy)
    proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    proxy_thread.start()

    def start(service, recovery=False):
        env = smoke.local.environment(service)
        env.update({"PORT": str(ports[service]), "SERVER_ADDRESS": "127.0.0.1",
                    "AUTH_URL": bases["auth"], "GAME_URL": bases["game"], "LOBBY_URL": bases["lobby"]})
        if service == "lobby":
            env.update({"GAME_URL": "http://127.0.0.1:" + str(proxy.server_port),
                        "LOBBY_RETRY_MS": "250" if recovery else "3600000"})
        jar = next(p for p in (ROOT / "services" / service / "build/libs").glob("*.jar")
                   if not p.name.endswith("-plain.jar"))
        log = (directory / (service + "-" + str(len(processes)) + ".log")).open("w")
        logs.append(log)
        process = subprocess.Popen([java, "-jar", str(jar)], env=env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        smoke.wait_health(bases[service], process)
        return process

    def stop(process):
        if process.poll() is not None:
            return
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()

    def api(player, path, body=None, key=None):
        headers = {"Authorization": "Bearer " + player["token"], "X-Client-Type": "NATIVE",
                   "X-User-Id": "spoofed", "X-Service-Token": "spoofed", "X-Client-IP": "spoofed"}
        if body is not None:
            headers["Idempotency-Key"] = key or str(uuid.uuid4())
        return exchange(bases["api-gateway"] + "/api/v1/" + path, "POST" if body is not None else "GET", body, headers)

    def state(player):
        status, result = api(player, "participation")
        assert status == 200
        return result["data"]

    def create(player):
        version = state(player)["version"]
        status, result = api(player, "lobbies", {"expectedVersion": version})
        assert status == 201, "Create failed: " + str(status)
        return result["data"]

    try:
        start("auth"); start("game")
        lobby = start("lobby")
        start("api-gateway")
        assert exchange(bases["api-gateway"] + "/api/v1/lobbies", "POST", {"expectedVersion": 0},
                        {"X-Client-Type": "NATIVE"})[0] == 401
        assert exchange(bases["lobby"] + "/api/v1/lobbies", "POST", {"expectedVersion": 0})[0] == 401
        players = []
        for _ in range(5):
            credentials = {"email": "lobby-" + uuid.uuid4().hex + "@example.com",
                           "password": "Aa1!" + secrets.token_hex(4), "clientType": "NATIVE"}
            headers = {"X-Client-Type": "NATIVE", "Idempotency-Key": str(uuid.uuid4())}
            account = smoke.request(bases["api-gateway"] + "/api/v1/auth/register", "POST", credentials, headers, 201)
            headers["Idempotency-Key"] = str(uuid.uuid4())
            login = smoke.request(bases["api-gateway"] + "/api/v1/auth/login", "POST", credentials, headers)
            players.append({"userId": str(uuid.UUID(account["data"]["userId"])), "token": login["data"]["token"]})
        owner = players[0]
        room = create(owner)
        assert len(room["code"]) == 10 and room["players"] == [owner["userId"]]
        assert api(players[1], "lobbies/" + room["lobbyId"])[0] == 404
        gate = threading.Barrier(3)

        def join(player):
            gate.wait(timeout=10)
            key = str(uuid.uuid4())
            body = {"expectedVersion": state(player)["version"], "code": room["code"]}
            return player, key, body, api(player, "lobbies/join", body, key)

        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            outcomes = list(pool.map(join, players[1:4]))
        winners = [row for row in outcomes if row[3][0] == 200]
        assert len(winners) == 1 and all(row[3][0] in (200, 404, 409) for row in outcomes)
        winner, key, body, response = winners[0]
        assert api(winner, "lobbies/join", body, key) == response
        assert response[1]["data"]["version"] == state(winner)["version"]
        match = state(owner)["resourceId"]
        assert state(owner)["state"] == "IN_MATCH" and state(winner)["resourceId"] == match
        assert sql("game", f"SELECT count(*) FROM match_member WHERE match_id='{match}' AND active;") == "2"
        assert sql("game", f"SELECT count(*) FROM game_match WHERE source_ref='{room['lobbyId']}';") == "1"
        assert sql("game", f"SELECT state FROM game_match WHERE match_id='{match}';") == "WAITING_READY"
        game_token = smoke.local.environment("game")["GAME_SERVICE_TOKEN"]
        saved_join = json.loads(sql("lobby", "SELECT game_payload FROM room_operation WHERE room_id="
                                    + f"'{room['lobbyId']}' AND user_id='{winner['userId']}' AND kind='JOIN';"))
        replayed = smoke.request(bases["game"] + "/internal/v1/participation/claim", "POST", saved_join,
                                {"X-Service-Token": game_token, "Idempotency-Key": saved_join["operationId"]})
        assert replayed["status"] == "APPLIED" and replayed["resourceId"] == match
        assert sql("game", f"SELECT count(*) FROM match_member WHERE match_id='{match}' AND active;") == "2"
        assert api(owner, "lobbies/" + room["lobbyId"] + "/leave", {"expectedVersion": state(owner)["version"]})[0] == 409
        assert state(owner)["state"] == "IN_MATCH"
        losers = [row[0] for row in outcomes if row[3][0] != 200]
        waiting = create(losers[0])
        barrier = threading.Barrier(2)

        def leave():
            barrier.wait(timeout=10)
            return api(losers[0], "lobbies/" + waiting["lobbyId"] + "/leave", {"expectedVersion": 1})

        def race_join():
            barrier.wait(timeout=10)
            return api(losers[1], "lobbies/join", {"expectedVersion": 0, "code": waiting["code"]})

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            a, b = pool.submit(leave), pool.submit(race_join)
            leave_result, join_result = a.result(), b.result()
        if leave_result[0] == 200:
            assert join_result[0] in (404, 409)
            assert state(losers[0])["state"] == "MENU" and state(losers[1])["state"] == "MENU"
        else:
            assert leave_result[0] == 409 and join_result[0] == 200
            assert state(losers[0])["state"] == "IN_MATCH" and state(losers[1])["state"] == "IN_MATCH"
        # Persist a pending creation after Game commit, then recover it in a new Lobby process.
        recovery = players[4]
        key = str(uuid.uuid4())
        drop.set()
        status, _ = api(recovery, "lobbies", {"expectedVersion": 0}, key)
        assert status == 503 and not drop.is_set()
        assert state(recovery)["state"] == "LOBBY"
        recovered_room = state(recovery)["resourceId"]
        assert sql("lobby", f"SELECT status FROM room WHERE room_id='{recovered_room}';") == "REGISTERING"
        assert api(owner, "lobbies/" + recovered_room)[0] == 404
        stop(lobby)
        start("lobby", recovery=True)
        deadline = time.monotonic() + 15
        while sql("lobby", f"SELECT status FROM room WHERE room_id='{recovered_room}';") != "WAITING":
            assert time.monotonic() < deadline, "Lobby did not reconcile the committed Game operation"
            time.sleep(0.1)
        first = api(recovery, "lobbies", {"expectedVersion": 0}, key)
        assert first[0] == 201 and first[1]["data"]["lobbyId"] == recovered_room
        assert api(recovery, "lobbies", {"expectedVersion": 0}, key) == first
        assert sql("lobby", f"SELECT count(*) FROM room WHERE owner_id='{recovery['userId']}';") == "1"
        # Manually resolve the original coordination operation; replay cannot allocate a second slot.
        operation = sql("lobby", f"SELECT operation_id FROM room_operation WHERE room_id='{recovered_room}';")
        game_token = smoke.local.environment("game")["GAME_SERVICE_TOKEN"]
        result = smoke.request(bases["game"] + "/internal/v1/operations/" + operation,
                               headers={"X-Service-Token": game_token})
        assert result["status"] == "APPLIED" and result["resourceId"] == recovered_room
        saved = json.loads(sql("lobby", f"SELECT game_payload FROM room_operation WHERE operation_id='{operation}';"))
        replayed = smoke.request(bases["game"] + "/internal/v1/rooms/register", "POST", saved,
                                {"X-Service-Token": game_token, "Idempotency-Key": operation})
        assert replayed == result
        assert sql("game", f"SELECT count(*) FROM room_slot WHERE room_id='{recovered_room}';") == "1"
        code = first[1]["data"]["code"]
        assert api(recovery, "lobbies/" + recovered_room + "/leave", {"expectedVersion": 1})[0] == 200
        assert state(recovery)["state"] == "MENU"
        assert api(recovery, "lobbies/join", {"expectedVersion": 2, "code": code})[0] == 404
        # Invalid codes count too; spoofing IP cannot bypass the authenticated user's limit.
        statuses = [api(recovery, "lobbies/join", {"expectedVersion": 2, "code": "invalid"})[0] for _ in range(10)]
        assert statuses[:-1] == [400] * 9 and statuses[-1] == 429
        print("Lobby runtime passed: protected routes, one last slot, shared ready match, join/leave race, lost-response recovery on restart, stable replay, closure and rate limit.")
        print("Created five local accounts and up to two WAITING_READY matches. Cancellation/return requires stage 7. Logs:", directory)
    finally:
        for process in reversed(processes):
            stop(process)
        proxy.shutdown()
        proxy.server_close()
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
