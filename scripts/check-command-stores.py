#!/usr/bin/env python3
"""Run real PostgreSQL command-store tests without printing local credentials."""

import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", choices=("kotlin", "go"))
    parser.add_argument("--service", choices=("auth", "user", "lobby", "game", "matchmaking", "realtime"))
    args = parser.parse_args()
    secrets = dict(line.split("=", 1) for line in (ROOT / "infra/local/.env").read_text().splitlines()
                   if line and not line.startswith("#"))
    shared_env = {**os.environ, "NATS_URL": "nats://127.0.0.1:4222", "NATS_USER": "app",
                  "NATS_PASSWORD": secrets["NATS_PASSWORD"], "REDIS_PASSWORD": secrets["REDIS_PASSWORD"]}
    if args.only != "go":
        for service in ("auth", "user", "lobby", "game"):
            if args.service and service != args.service:
                continue
            env = {**shared_env, "PG_JDBC_URL": "jdbc:postgresql://127.0.0.1:5432/" + service,
                   "PGUSER": service, "PGPASSWORD": secrets[service.upper() + "_DB_PASSWORD"]}
            subprocess.run([str(ROOT / "services" / service / "gradlew"), "-p",
                            str(ROOT / "services" / service), "ktlintFormat", "clean", "build",
                            "--no-daemon"], env=env, check=True)
    if args.only != "kotlin":
        for service in ("matchmaking", "realtime"):
            if args.service and service != args.service:
                continue
            env = {**shared_env, "PG_DSN": "host=127.0.0.1 port=5432 user=" + service
                   + " dbname=" + service + " password=" + secrets[service.upper() + "_DB_PASSWORD"]
                   + " sslmode=disable"}
            subprocess.run(["go", "test", "-race", "-count=1", "./..."],
                           cwd=ROOT / "services" / service, env=env, check=True)


if __name__ == "__main__":
    main()
