#!/usr/bin/env python3
"""Smoke actual Auth/Gateway/User jars against the local stack (creates one local test account)."""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

import importlib.util

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("local_service", ROOT / "scripts/run-service.py")
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)


def port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def request(url, method="GET", body=None, headers=None, status=200):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        assert response.status == status, "Unexpected HTTP status: " + str(response.status)
        content = response.read()
        return json.loads(content) if content else None


def wait_health(base, process):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Service exited; inspect smoke log directory")
        try:
            request(base + "/actuator/health")
            return
        except (OSError, AssertionError):
            time.sleep(0.1)
    raise RuntimeError("Service health timeout")


def profile_count(user_id):
    # user_id is a UUID generated/returned by this smoke, never untrusted SQL text.
    checked = str(uuid.UUID(user_id))
    command = ["docker", "compose", "--env-file", str(ROOT / "infra/local/.env"), "-f",
               str(ROOT / "infra/local/compose.yml"), "exec", "-T", "postgres", "bash", "-ec",
               'export PGPASSWORD="$USER_DB_PASSWORD"; exec psql -X -h 127.0.0.1 -U user -d user -At -c "$1"',
               "profile-count", "SELECT count(*) FROM profiles WHERE user_id='" + checked + "'"]
    return int(subprocess.check_output(command, text=True).strip())


def main():
    processes = []
    logs = []
    directory = Path(tempfile.mkdtemp(prefix="ttt-auth-smoke-"))
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
    ports = {name: port() for name in ("auth", "user", "api-gateway")}
    auth_base = "http://127.0.0.1:" + str(ports["auth"])
    base = "http://127.0.0.1:" + str(ports["api-gateway"])

    def start(service):
        env = local.environment(service)
        env.update({"PORT": str(ports[service]), "SERVER_ADDRESS": "127.0.0.1", "AUTH_URL": auth_base})
        jar = next(p for p in (ROOT / "services" / service / "build/libs").glob("*.jar") if not p.name.endswith("-plain.jar"))
        log = (directory / (service + ".log")).open("w")
        logs.append(log)
        process = subprocess.Popen([java, "-jar", str(jar)], env=env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        return process

    try:
        auth = start("auth")
        wait_health(auth_base, auth)
        gateway = start("api-gateway")
        wait_health(base, gateway)
        native = {"X-Client-Type": "NATIVE", "Idempotency-Key": str(uuid.uuid4())}
        password = "Aa1!" + secrets.token_hex(4)
        credentials = {"email": "runtime-" + uuid.uuid4().hex + "@example.com", "password": password, "clientType": "NATIVE"}
        account = request(base + "/api/v1/auth/register", "POST", credentials, native, 201)
        user_id = account["data"]["userId"]
        assert profile_count(user_id) == 0, "User must still be stopped"
        login_headers = {**native, "Idempotency-Key": str(uuid.uuid4())}
        session = request(base + "/api/v1/auth/login", "POST", credentials, login_headers)
        token = session["data"]["token"]
        replay = request(base + "/api/v1/auth/login", "POST", credentials, login_headers)
        assert replay["data"]["token"] == token
        conflict = request(base + "/api/v1/auth/login", "POST", credentials,
                           {**native, "Idempotency-Key": str(uuid.uuid4())}, 409)
        assert conflict["code"] == "SESSION_ALREADY_ACTIVE"
        authorized = {"Authorization": "Bearer " + token, "Idempotency-Key": str(uuid.uuid4())}
        request(base + "/api/v1/auth/session", headers=authorized)
        ticket = request(base + "/api/v1/auth/ws-ticket", "POST", headers=authorized)["data"]["ticket"]
        internal = {"X-Service-Token": local.environment("auth")["AUTH_SERVICE_TOKEN"]}
        assert request(auth_base + "/internal/v1/ws-tickets/consume", "POST", {"ticket": ticket}, internal)["active"]
        assert not request(auth_base + "/internal/v1/ws-tickets/consume", "POST", {"ticket": ticket}, internal)["active"]
        user = start("user")
        wait_health("http://127.0.0.1:" + str(ports["user"]), user)
        deadline = time.monotonic() + 15
        while profile_count(user_id) != 1:
            assert time.monotonic() < deadline, "User profile was not created from outbox/JetStream"
            time.sleep(0.1)
        print("Runtime smoke passed: registration/login while User stopped, encrypted replay, single session, introspection, ticket consume, delayed User profile.")
        print("Created one test account in the local stack; no credentials printed. Logs:", directory)
    finally:
        for process in reversed(processes):
            process.terminate()
        for process in reversed(processes):
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
