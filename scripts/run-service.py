#!/usr/bin/env python3
"""Start a service using its local.env against the local dependencies without exposing secrets in argv."""
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
    # Serialize upgrades so parallel local starts cannot read a partially rewritten file.
    import fcntl
    with credentials.open("r+") as output:
        fcntl.flock(output, fcntl.LOCK_EX)
        values = json.load(output)
        if "lobby" not in values or "matchmaking" not in values:
            values.setdefault("lobby", secrets.token_urlsafe(32))
            values.setdefault("matchmaking", secrets.token_urlsafe(32))
            output.seek(0)
            json.dump(values, output)
            output.truncate()
    return values, key_file


SERVICES = ("auth", "user", "api-gateway", "lobby", "game", "matchmaking", "realtime")


def read_env(path):
    """Read literal KEY=value entries; never evaluate shell code or interpolate secrets."""
    values = {}
    for number, line in enumerate(path.read_text().splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key.isidentifier() or not key.isascii():
            raise ValueError(f"Invalid environment entry at {path}:{number}")
        values[key] = value
    return values


def environment(service):
    if service not in SERVICES:
        raise ValueError("Unknown service")
    env = read_env(ROOT / "services" / service / "local.env")
    if service in ("auth", "user", "game", "lobby", "matchmaking"):
        values = read_env(ROOT / "infra/local/.env")
        env.update({"PGPASSWORD": values[service.upper() + "_DB_PASSWORD"],
                    "NATS_PASSWORD": values["NATS_PASSWORD"]})
        if service == "auth":
            env["REDIS_PASSWORD"] = values["REDIS_PASSWORD"]
    if service in ("auth", "api-gateway", "game", "lobby", "matchmaking"):
        credentials, key_file = local_credentials()
        env.update({"AUTH_SERVICE_TOKEN": credentials["auth"],
                    "GAME_SERVICE_TOKEN": credentials["game"],
                    "LOBBY_SERVICE_TOKEN": credentials["lobby"],
                    "MATCHMAKING_SERVICE_TOKEN": credentials["matchmaking"],
                    "AUTH_LOGIN_KEY_FILE": str(key_file)})
    # Explicit shell/IDE values override both local settings and generated credentials.
    return {**env, **os.environ}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=SERVICES)
    args = parser.parse_args()
    directory = ROOT / "services" / args.service
    command = (["go", "run", "."] if args.service in ("matchmaking", "realtime") else
               [str(directory / "gradlew"), "-p", str(directory), "bootRun", "--no-daemon"])
    subprocess.run(command, cwd=directory, env=environment(args.service), check=True)


if __name__ == "__main__":
    main()
