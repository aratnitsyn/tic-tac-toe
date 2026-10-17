#!/usr/bin/env python3
"""Create bounded local JetStream streams without logging credentials."""

import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
secrets = dict(line.split("=", 1) for line in (ROOT / "infra/local/.env").read_text().splitlines()
               if line and not line.startswith("#"))
env = {**os.environ, "NATS_URL": "nats://127.0.0.1:4222", "NATS_USER": "app",
       "NATS_PASSWORD": secrets["NATS_PASSWORD"]}
subprocess.run(["go", "run", "./cmd/bootstrap-streams", "-config",
                str(ROOT / "infra/local/jetstream.json")],
               cwd=ROOT / "services/matchmaking", env=env, check=True)
