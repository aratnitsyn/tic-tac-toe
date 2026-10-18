#!/usr/bin/env python3
"""Real Gateway/Auth/Game/Matchmaking smoke; creates four accounts and one WAITING_READY match.

Drops an AssignPair response after Game commit and restarts Matchmaking to resolve RESERVED.
Local test data is retained; the script stops only its own processes.
"""
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
spec = importlib.util.spec_from_file_location("lobby_smoke", ROOT / "scripts/check-lobby-runtime.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
smoke, exchange = helper.smoke, helper.exchange


def sql(service, statement):
    assert service in ("matchmaking", "game")
    command = ["docker", "compose", "--env-file", str(ROOT / "infra/local/.env"), "-f",
               str(ROOT / "infra/local/compose.yml"), "exec", "-T", "postgres", "bash", "-ec",
               'export PGPASSWORD="$' + service.upper() + '_DB_PASSWORD"; exec psql -X -h 127.0.0.1 -U '
               + service + ' -d ' + service + ' -v ON_ERROR_STOP=1 -At']
    return subprocess.check_output(command, input=statement, text=True).strip()


def main():
    directory = Path(tempfile.mkdtemp(prefix="ttt-matchmaking-smoke-"))
    processes, logs = [], []
    ports = {name: smoke.port() for name in ("auth", "game", "matchmaking", "api-gateway")}
    bases = {name: "http://127.0.0.1:" + str(port) for name, port in ports.items()}
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
    dropped = threading.Event()

    class Proxy(BaseHTTPRequestHandler):
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
                if self.command == "POST" and self.path.endswith("assign-pair") and not dropped.is_set():
                    dropped.set()
                    self.close_connection = True
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

    proxy = ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
    threading.Thread(target=proxy.serve_forever, daemon=True).start()

    def start(service, interval="3600000"):
        env = smoke.local.environment(service)
        env.update({"PORT": str(ports[service]), "SERVER_ADDRESS": "127.0.0.1",
                    "AUTH_URL": bases["auth"], "GAME_URL": bases["game"], "MATCHMAKING_URL": bases["matchmaking"]})
        if service == "matchmaking":
            env.update({"GAME_URL": "http://127.0.0.1:" + str(proxy.server_port), "MATCHMAKING_RETRY_MS": interval})
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
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Matchmaking exited; inspect logs: " + str(directory))
                try:
                    if exchange(bases[service] + "/healthz")[0] == 200:
                        break
                except OSError:
                    pass
                time.sleep(0.05)
            else:
                raise RuntimeError("Matchmaking health timeout")
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

    def api(player, path, body=None, key=None):
        headers = {"Authorization": "Bearer " + player["token"], "X-Client-Type": "NATIVE",
                   "X-Service-Token": "spoofed", "X-User-Id": str(uuid.uuid4())}
        if body is not None:
            headers["Idempotency-Key"] = key or str(uuid.uuid4())
        return exchange(bases["api-gateway"] + "/api/v1/" + path, "POST" if body is not None else "GET", body, headers)

    def state(player):
        status, body = api(player, "participation")
        assert status == 200
        return body["data"]

    try:
        start("auth"); start("game")
        matchmaking = start("matchmaking")
        start("api-gateway")
        assert exchange(bases["matchmaking"] + "/api/v1/matchmaking/queue")[0] == 401
        assert exchange(bases["api-gateway"] + "/api/v1/matchmaking/queue")[0] == 401
        players = []
        for _ in range(4):
            credentials = {"email": "queue-" + uuid.uuid4().hex + "@example.com",
                           "password": "Aa1!" + secrets.token_hex(4), "clientType": "NATIVE"}
            headers = {"X-Client-Type": "NATIVE", "Idempotency-Key": str(uuid.uuid4())}
            account = smoke.request(bases["api-gateway"] + "/api/v1/auth/register", "POST", credentials, headers, 201)
            headers["Idempotency-Key"] = str(uuid.uuid4())
            login = smoke.request(bases["api-gateway"] + "/api/v1/auth/login", "POST", credentials, headers)
            players.append({"userId": account["data"]["userId"], "token": login["data"]["token"]})
        tickets = []
        for player in players[:3]:
            key = str(uuid.uuid4())
            status, body = api(player, "matchmaking/queue", {"expectedVersion": state(player)["version"]}, key)
            assert status == 201, (status, body)
            assert api(player, "matchmaking/queue", {"expectedVersion": 0}, key) == (status, body)
            assert api(player, "matchmaking/queue", {"expectedVersion": 1}, key)[0] == 409
            assert state(player)["state"] == "QUEUED"
            tickets.append(body["data"])
        assert [int(t["originalSequence"]) for t in tickets] == sorted(int(t["originalSequence"]) for t in tickets)
        assert api(players[3], "matchmaking/queue")[0] == 404
        stop(matchmaking)
        matchmaking = start("matchmaking", "1500")
        assert dropped.wait(timeout=15), "AssignPair was not executed"
        stop(matchmaking)
        ids = ",".join("'" + t["ticketId"] + "'" for t in tickets[:2])
        assert sql("matchmaking", "SELECT count(*) FROM queue_ticket WHERE ticket_id IN (" + ids + ") AND status='RESERVED'") == "2"
        first, second = state(players[0]), state(players[1])
        assert first["state"] == second["state"] == "IN_MATCH" and first["resourceId"] == second["resourceId"]
        operation = sql("matchmaking", "SELECT pair_id FROM queue_ticket WHERE ticket_id='" + tickets[0]["ticketId"] + "'")
        matchmaking = start("matchmaking", "250")
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if all(api(p, "matchmaking/queue")[1]["data"]["status"] == "CONSUMED" for p in players[:2]):
                break
            time.sleep(0.05)
        else:
            raise AssertionError("RESERVED did not reconcile")
        replay_env = {**smoke.local.environment("matchmaking"), "GAME_URL": bases["game"]}
        with (directory / "manual-replay.log").open("w") as replay_log:
            subprocess.run(["go", "run", "./cmd/reconcile-queue", "-operation", operation],
                           cwd=ROOT / "services/matchmaking", env=replay_env,
                           stdout=replay_log, stderr=subprocess.STDOUT, check=True)
        assert sql("game", "SELECT count(*) FROM game_match WHERE operation_id='" + operation + "'") == "1"
        assert api(players[0], "matchmaking/queue/cancel", {"expectedVersion": first["version"]})[0] == 409
        assert state(players[0])["resourceId"] == first["resourceId"]
        assert api(players[2], "matchmaking/queue")[1]["data"]["status"] == "AVAILABLE"
        cancel_key = str(uuid.uuid4())
        cancel_body = {"expectedVersion": state(players[2])["version"]}
        result = api(players[2], "matchmaking/queue/cancel", cancel_body, cancel_key)
        assert result[0] == 200 and result[1]["data"]["state"] == "MENU"
        assert api(players[2], "matchmaking/queue/cancel", cancel_body, cancel_key) == result
        assert api(players[2], "matchmaking/queue")[1]["data"]["status"] == "CANCELLED"
        assert state(players[2])["state"] == "MENU"
        print("FIFO, trusted Gateway identity, admission replay, lost AssignPair response, restart recovery and cancellation passed.")
        print("Logs:", directory)
    finally:
        for process in reversed(processes):
            stop(process)
        proxy.shutdown(); proxy.server_close()
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
