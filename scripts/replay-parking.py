#!/usr/bin/env python3
"""Replay one explicitly selected local parking message with a stable operation ID."""

import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("sequence", type=int)
parser.add_argument("operation_id")
parser.add_argument("--confirm", action="store_true")
args = parser.parse_args()
if not args.confirm:
    parser.error("--confirm is required to publish a replay")
secrets = dict(line.split("=", 1) for line in (ROOT / "infra/local/.env").read_text().splitlines()
               if line and not line.startswith("#"))
env = {**os.environ, "NATS_URL": "nats://127.0.0.1:4222", "NATS_USER": "app",
       "NATS_PASSWORD": secrets["NATS_PASSWORD"]}
subprocess.run(["go", "run", "./cmd/replay-parking", "--sequence", str(args.sequence),
                "--operation-id", args.operation_id, "--confirm"],
               cwd=ROOT / "services/matchmaking", env=env, check=True)
