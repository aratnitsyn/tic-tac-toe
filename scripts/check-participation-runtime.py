#!/usr/bin/env python3
"""Exercise Game coordination across process restarts (creates two local test accounts and a match)."""
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("auth_smoke", ROOT / "scripts/check-auth-runtime.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def sql(statement):
    command = ["docker", "compose", "--env-file", str(ROOT / "infra/local/.env"), "-f",
               str(ROOT / "infra/local/compose.yml"), "exec", "-T", "postgres", "bash", "-ec",
               'export PGPASSWORD="$GAME_DB_PASSWORD"; exec psql -X -h 127.0.0.1 -U game -d game -v ON_ERROR_STOP=1 -At']
    return subprocess.check_output(command, input=statement, text=True).strip()


def main():
    directory = Path(tempfile.mkdtemp(prefix="ttt-participation-smoke-"))
    processes, logs = [], []
    ports = {name: smoke.port() for name in ("auth", "api-gateway", "game")}
    bases = {name: "http://127.0.0.1:" + str(port) for name, port in ports.items()}
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
    game_token = smoke.local.environment("game")["GAME_SERVICE_TOKEN"]

    def start(service):
        env = smoke.local.environment(service)
        env.update({"PORT": str(ports[service]), "SERVER_ADDRESS": "127.0.0.1",
                    "AUTH_URL": bases["auth"], "GAME_URL": bases["game"]})
        jar = next(p for p in (ROOT / "services" / service / "build/libs").glob("*.jar")
                   if not p.name.endswith("-plain.jar"))
        log = (directory / (service + "-" + str(len(processes)) + ".log")).open("w")
        logs.append(log)
        process = subprocess.Popen([java, "-jar", str(jar)], env=env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        smoke.wait_health(bases[service], process)
        return process

    def stop(process):
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()

    def internal(path, body=None, status=200):
        headers = {"X-Service-Token": game_token, "X-Correlation-Id": correlation}
        if body:
            headers["Idempotency-Key"] = body["operationId"]
        return smoke.request(bases["game"] + "/internal/v1/" + path,
                             "POST" if body else "GET", body, headers, status)

    correlation = str(uuid.uuid4())
    try:
        start("auth")
        start("api-gateway")
        game = start("game")
        smoke.request(bases["api-gateway"] + "/api/v1/participation", status=401)
        players = []
        for index in range(2):
            credentials = {"email": "coordination-" + uuid.uuid4().hex + "@example.com",
                           "password": "Aa1!" + secrets.token_hex(4), "clientType": "NATIVE"}
            headers = {"X-Client-Type": "NATIVE", "Idempotency-Key": str(uuid.uuid4())}
            account = smoke.request(bases["api-gateway"] + "/api/v1/auth/register", "POST", credentials, headers, 201)
            headers["Idempotency-Key"] = str(uuid.uuid4())
            login = smoke.request(bases["api-gateway"] + "/api/v1/auth/login", "POST", credentials, headers)
            user_id = str(uuid.UUID(account["data"]["userId"]))
            token = login["data"]["token"]
            snapshot = smoke.request(bases["api-gateway"] + "/api/v1/participation",
                                     headers={"Authorization": "Bearer " + token, "X-User-Id": "spoofed"})
            assert snapshot["data"]["userId"] == user_id and snapshot["data"]["state"] == "MENU"
            ticket = str(uuid.uuid4())
            claim = {"operationId": str(uuid.uuid4()), "userId": user_id, "expectedVersion": 0,
                     "target": "QUEUED", "resourceId": ticket}
            assert internal("participation/claim", claim)["status"] == "APPLIED"
            players.append({"userId": user_id, "expectedVersion": 1, "ticketId": ticket,
                            "originalSequence": str(index + 1), "token": token})
        stop(game)
        operation_id, match_id = str(uuid.uuid4()), str(uuid.uuid4())
        request = {"operationId": operation_id, "matchId": match_id,
                   "players": [{k: v for k, v in player.items() if k != "token"} for player in players]}
        # Model the committed reservation stage while Game is stopped. All interpolated identifiers
        # are locally generated and UUID-validated; the payload never contains credentials.
        payload = json.dumps(request).replace("'", "''")
        user_ids = ",".join("'" + player["userId"] + "'" for player in players)
        sql("BEGIN; INSERT INTO coordination_operation(operation_id,kind,payload,correlation_id,candidate_id,status) "
            f"VALUES ('{operation_id}','ASSIGN_PAIR','{payload}'::jsonb,'{correlation}','{match_id}','PENDING'); "
            "INSERT INTO coordination_member(operation_id,user_id) "
            f"SELECT '{operation_id}',user_id FROM participation WHERE user_id IN ({user_ids}); "
            f"UPDATE participation SET operation_id='{operation_id}' WHERE user_id IN ({user_ids}) "
            "AND state='QUEUED' AND version=1 AND operation_id IS NULL; "
            f"DO $$ BEGIN IF (SELECT count(*) FROM participation WHERE operation_id='{operation_id}') <> 2 "
            "THEN RAISE EXCEPTION 'Invalid fixture reservation'; END IF; END $$; COMMIT;")
        assert sql(f"SELECT status FROM coordination_operation WHERE operation_id='{operation_id}';") == "PENDING"
        game = start("game")
        deadline = time.monotonic() + 10
        while internal("operations/" + operation_id)["status"] != "APPLIED":
            assert time.monotonic() < deadline, "Persisted operation was not recovered after restart"
            time.sleep(0.1)
        first = internal("matches/assign-pair", request)
        assert first["resourceId"] == match_id
        for player in players:
            snapshot = smoke.request(bases["api-gateway"] + "/api/v1/participation",
                                     headers={"Authorization": "Bearer " + player["token"]})["data"]
            assert snapshot["state"] == "IN_MATCH" and snapshot["resourceId"] == match_id
        stop(game)
        start("game")
        assert internal("matches/assign-pair", request) == first
        assert sql(f"SELECT count(*) FROM game_match WHERE match_id='{match_id}';") == "1"
        assert sql("SELECT count(*) FROM outbox WHERE envelope->>'correlationId'="
                   f"'{correlation}';") == "5"
        assert sql("SELECT correlation_id FROM game_match WHERE match_id=" + f"'{match_id}';") == correlation
        linked_logs = [line for path in directory.glob("*.log") for line in path.read_text().splitlines()
                       if "operationId=" + operation_id in line and "correlationId=" + correlation in line]
        assert linked_logs, "Committed coordination log cannot be correlated to its operation"
        print("Participation runtime passed: authenticated snapshot, pending recovery on Game restart, stable pair replay, correlated outbox.")
        print("Created two local test accounts and one WAITING_READY match; no credentials printed. Logs:", directory)
    finally:
        for process in reversed(processes):
            stop(process)
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
