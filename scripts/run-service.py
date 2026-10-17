#!/usr/bin/env python3
"""Start an Auth/User/Gateway process against the local dependencies without exposing secrets in argv."""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def local_credentials():
    directory = ROOT / "infra/local/secrets"
    directory.mkdir(mode=0o700, exist_ok=True)
    credentials = directory / "services.json"
    if not credentials.exists():
        descriptor = os.open(credentials, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            json.dump({"auth": secrets.token_urlsafe(32), "game": secrets.token_urlsafe(32)}, output)
    key_file = directory / "login-keys.properties"
    if not key_file.exists():
        descriptor = os.open(key_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            output.write("local-v1=" + base64.b64encode(secrets.token_bytes(32)).decode() + "\n")
    return json.loads(credentials.read_text()), key_file


def environment(service):
    values = dict(line.split("=", 1) for line in (ROOT / "infra/local/.env").read_text().splitlines()
                  if line and not line.startswith("#"))
    credentials, key_file = local_credentials()
    env = {**os.environ, "AUTH_SERVICE_TOKEN": credentials["auth"], "GAME_SERVICE_TOKEN": credentials["game"],
           "AUTH_LOGIN_KEY_FILE": str(key_file), "AUTH_LOGIN_KEY_ID": "local-v1",
           "NATS_URL": "nats://127.0.0.1:4222", "NATS_USER": "app", "NATS_PASSWORD": values["NATS_PASSWORD"],
           "REDIS_PASSWORD": values["REDIS_PASSWORD"]}
    if service in ("auth", "user"):
        env.update({"PG_JDBC_URL": "jdbc:postgresql://127.0.0.1:5432/" + service,
                    "PGUSER": service, "PGPASSWORD": values[service.upper() + "_DB_PASSWORD"]})
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=("auth", "user", "api-gateway"))
    args = parser.parse_args()
    subprocess.run([str(ROOT / "services" / args.service / "gradlew"), "-p",
                    str(ROOT / "services" / args.service), "bootRun", "--no-daemon"],
                   env=environment(args.service), check=True)


if __name__ == "__main__":
    main()
