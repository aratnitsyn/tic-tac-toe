#!/usr/bin/env python3
"""Measure FIFO selection under contention in an isolated PostgreSQL schema (removed after run)."""
import importlib.util
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("local", ROOT / "scripts/run-service.py")
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)

if __name__ == "__main__":
    env = local.environment("matchmaking")
    subprocess.run(["go", "test", "-count=1", "-run", "^TestQueueControlBenchmark$", "-v", "./internal/queue"],
                   cwd=ROOT / "services/matchmaking", env=env, check=True)
