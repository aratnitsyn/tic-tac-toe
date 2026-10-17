#!/usr/bin/env python3
"""Run real PostgreSQL command-store tests without printing local credentials."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", choices=("kotlin", "go"))
    parser.add_argument("--service", choices=("auth", "user", "lobby", "game", "matchmaking", "realtime"))
    args = parser.parse_args()
    if args.service:
        language = "go" if args.service in ("matchmaking", "realtime") else "kotlin"
        if args.only and args.only != language:
            parser.error("--service is incompatible with --only; no tests would run")
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
            reports = [ET.parse(path).getroot() for path in
                       (ROOT / "services" / service / "build/test-results/test").glob("TEST-*.xml")]
            counts = {key: sum(int(report.get(key, "0")) for report in reports)
                      for key in ("tests", "failures", "errors", "skipped")}
            if not counts["tests"] or any(counts[key] for key in ("failures", "errors", "skipped")):
                raise RuntimeError(f"Incomplete integration run for {service}: {counts}")
            print(f"{service}: {counts['tests']} tests passed, none skipped", flush=True)
    if args.only != "kotlin":
        for service in ("matchmaking", "realtime"):
            if args.service and service != args.service:
                continue
            env = {**shared_env, "PG_DSN": "host=127.0.0.1 port=5432 user=" + service
                   + " dbname=" + service + " password=" + secrets[service.upper() + "_DB_PASSWORD"]
                   + " sslmode=disable"}
            result = subprocess.run(["go", "test", "-race", "-count=1", "-json", "./..."],
                                    cwd=ROOT / "services" / service, env=env, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            if result.returncode:
                print(result.stdout, flush=True)
                result.check_returncode()
            events = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
            passed = sum(event.get("Action") == "pass" and "Test" in event for event in events)
            skipped = [event["Test"] for event in events if event.get("Action") == "skip" and "Test" in event]
            if not passed or skipped:
                raise RuntimeError(f"Incomplete integration run for {service}: {passed} passed, skipped={skipped}")
            print(f"{service}: {passed} tests passed with race detector, none skipped", flush=True)


if __name__ == "__main__":
    main()
