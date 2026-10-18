#!/usr/bin/env python3
"""Replay one existing Matchmaking operation without changing its persisted request or FIFO order."""
import argparse
import importlib.util
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("local", ROOT / "scripts/run-service.py")
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", type=uuid.UUID)
    args = parser.parse_args()
    subprocess.run(["go", "run", "./cmd/reconcile-queue", "-operation", str(args.operation)],
                   cwd=ROOT / "services/matchmaking", env=local.environment("matchmaking"), check=True)
