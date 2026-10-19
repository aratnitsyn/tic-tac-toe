#!/usr/bin/env python3
"""Real logout smoke: Auth CLOSING, Game cleanup and restart after lost acknowledgement.

Creates local accounts/matches; retains data and stops only its own processes.
"""
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid
import threading
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("lobby_smoke", ROOT / "scripts/check-lobby-runtime.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
smoke, exchange = helper.smoke, helper.exchange


def main():
    directory = Path(tempfile.mkdtemp(prefix="ttt-logout-smoke-"))
    names = ("auth", "game", "lobby", "matchmaking", "api-gateway")
    bases = {name: "http://127.0.0.1:" + str(smoke.port()) for name in names}
    processes, logs = [], []
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")

    drop = threading.Event()
    committed = threading.Event()

    class GameProxy(BaseHTTPRequestHandler):
        def do_POST(self):
            size = int(self.headers.get("Content-Length", "0"))
            headers = {k: v for k, v in self.headers.items()
                       if k.lower() not in ("host", "content-length", "connection")}
            request = urllib.request.Request(bases["game"] + self.path,
                                             data=self.rfile.read(size), method="POST", headers=headers)
            try:
                response = urllib.request.urlopen(request, timeout=10)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                body, status = response.read(), response.status
            if drop.is_set() and self.path == "/internal/v1/participation/logout" and status == 200:
                committed.set()
                self.close_connection = True
                return
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass  # Never log headers or credentials.

    proxy = ThreadingHTTPServer(("127.0.0.1", smoke.port()), GameProxy)
    proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    proxy_thread.start()

    def start(service):
        env = smoke.local.environment(service)
        env.update({"PORT": bases[service].rsplit(":", 1)[1], "SERVER_ADDRESS": "127.0.0.1",
                    "AUTH_URL": bases["auth"], "GAME_URL": bases["game"], "LOBBY_URL": bases["lobby"],
                    "COORDINATION_RETRY_DELAY_MS": "100", "LOBBY_RETRY_MS": "100",
                    "MATCHMAKING_URL": bases["matchmaking"], "MATCHMAKING_RETRY_MS": "3600000"})
        if service == "auth":
            env["GAME_URL"] = "http://127.0.0.1:" + str(proxy.server_address[1])
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
        if service == "matchmaking":
            wait(lambda: exchange(bases[service] + "/healthz")[0] == 200)
        else:
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
            try:
                if check():
                    return
            except urllib.error.URLError:
                pass  # A newly started service may not yet listen.
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

    def pair(a, b, phase="RUNNING"):
        result = api(a, "lobbies", {"expectedVersion": state(a)["version"]})
        assert result[0] == 201, result
        result = api(b, "lobbies/join", {"expectedVersion": state(b)["version"], "code": result[1]["data"]["code"]})
        assert result[0] == 200, result
        identifier = result[1]["data"]["resourceId"]
        if phase == "WAITING_READY":
            return identifier
        for player in (a, b):
            version = data(player, "matches/" + identifier)["version"]
            assert api(player, "matches/" + identifier + "/ready", {"expectedVersion": version})[0] == 200
        if phase == "COUNTDOWN":
            return identifier
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

    def login(player):
        return exchange(bases["api-gateway"] + "/api/v1/auth/login", "POST", player["credentials"],
                        {"X-Client-Type": "NATIVE", "Idempotency-Key": str(uuid.uuid4())})

    def logout(player):
        result = api(player, "auth/logout", {})
        assert result[0] == 202, result[0]
        return result[1]["data"]["operationId"]

    try:
        auth_process = start("auth")
        start("game")
        start("lobby")
        start("matchmaking")
        start("api-gateway")
        players = []
        for _ in range(3):
            credentials = {"email": "logout-" + uuid.uuid4().hex + "@example.com",
                           "password": "Aa1!" + secrets.token_hex(4), "clientType": "NATIVE"}
            headers = {"X-Client-Type": "NATIVE", "Idempotency-Key": str(uuid.uuid4())}
            account = smoke.request(bases["api-gateway"] + "/api/v1/auth/register", "POST", credentials, headers, 201)
            headers["Idempotency-Key"] = str(uuid.uuid4())
            result = smoke.request(bases["api-gateway"] + "/api/v1/auth/login", "POST", credentials, headers)
            players.append({"userId": account["data"]["userId"], "token": result["data"]["token"],
                            "credentials": credentials})
        a, b, c = players

        def complete_and_relogin(player):
            command = ["docker", "compose", "--env-file", str(ROOT / "infra/local/.env"), "-f",
                       str(ROOT / "infra/local/compose.yml"), "exec", "-T", "postgres", "bash", "-ec",
                       'export PGPASSWORD="$AUTH_DB_PASSWORD"; exec psql -X -h 127.0.0.1 -U auth -d auth -v ON_ERROR_STOP=1 -At']
            statement = "SELECT state FROM session_slots WHERE user_id='" + player["userId"] + "'"
            wait(lambda: subprocess.check_output(command, input=statement, text=True).strip() == "CLOSED")
            result = login(player)
            assert result[0] == 200, result[0]
            player["token"] = result[1]["data"]["token"]

        # MENU and waiting Lobby; the invitation must become unusable through real NATS delivery.
        old = a["token"]
        logout(a)
        complete_and_relogin(a)
        assert a["token"] != old
        room = api(a, "lobbies", {"expectedVersion": state(a)["version"]})
        assert room[0] == 201
        room_id, code = room[1]["data"]["lobbyId"], room[1]["data"]["code"]
        logout(a)
        complete_and_relogin(a)
        wait(lambda: helper.sql("lobby", "SELECT status FROM room WHERE room_id='" + room_id + "'") == "CLOSED")
        assert api(c, "lobbies/join", {"expectedVersion": state(c)["version"], "code": code})[0] == 404

        # Single queued player cannot be paired; logout must remove the durable ticket too.
        assert api(a, "matchmaking/queue", {"expectedVersion": state(a)["version"]})[0] == 201
        logout(a)
        complete_and_relogin(a)
        wait(lambda: data(a, "matchmaking/queue")["status"] == "CANCELLED")
        assert state(a)["state"] == "MENU"

        for phase in ("WAITING_READY", "COUNTDOWN"):
            identifier = pair(a, b, phase)
            logout(a)
            complete_and_relogin(a)
            wait(lambda: state(b)["state"] == "LOBBY")
            assert state(a)["state"] == "MENU"
            assert data(b, "matches/" + identifier)["state"] == "CANCELLED"
            wait(lambda: helper.sql("lobby", "SELECT count(*) FROM room WHERE owner_id='" + b["userId"] + "' AND status='WAITING'") == "1")
            room_b = helper.sql("lobby", "SELECT room_id FROM room WHERE owner_id='" + b["userId"] + "' AND status='WAITING'")
            assert api(b, "lobbies/" + room_b + "/leave", {"expectedVersion": state(b)["version"]})[0] == 200

        a = c  # Keep each account below the real Auth limit; no production limiter bypass.
        for paused in (False, True):
            identifier = pair(a, b)
            if paused:
                observe(a, identifier, False, 0)
            logout(a)
            complete_and_relogin(a)
            result = data(b, "games/" + identifier)
            assert result["result"]["reason"] == "SURRENDER"
            assert result["result"]["loserId"] == a["userId"]
            assert state(a)["state"] == state(b)["state"] == "MENU"

        # Game commits, every acknowledgement is lost; Auth must stay CLOSING even after retries.
        identifier = pair(a, b)
        observe(a, identifier, False, 0)
        drop.set()
        operation = logout(a)
        assert committed.wait(timeout=15)
        wait(lambda: state(b)["state"] == "MENU")
        result = data(b, "games/" + identifier)
        assert result["result"]["reason"] == "SURRENDER"
        assert api(a, "participation")[0] == 401
        assert login(a)[0] == 409
        auth_process.kill()
        auth_process.wait(timeout=10)
        drop.clear()
        auth_process = start("auth")
        complete_and_relogin(a)
        assert data(a, "games/" + identifier) == result
        assert helper.sql("game", "SELECT count(*) FROM outbox WHERE aggregate_id='" + identifier + "' AND event_type='GameFinished'") == "1"
        assert helper.sql("game", "SELECT count(*) FROM command_results WHERE command_id='" + operation + "' AND operation_type='LOGOUT'") == "1"
        print("Logout MENU/Lobby/preparation/active/paused passed; lost Game acknowledgement and Auth SIGKILL recover once; new login blocked during CLOSING.")
        print("Logs:", directory)
    finally:
        for process in reversed(processes):
            stop(process)
        proxy.shutdown()
        proxy.server_close()
        proxy_thread.join(timeout=5)
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
