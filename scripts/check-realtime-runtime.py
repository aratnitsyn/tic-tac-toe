#!/usr/bin/env python3
"""Own-process WS smoke against real services; retains test data, never logs tokens."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("lobby_smoke", ROOT / "scripts/check-lobby-runtime.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
smoke, exchange = helper.smoke, helper.exchange


def main():
    directory = Path(tempfile.mkdtemp(prefix="ttt-realtime-smoke-"))
    names = ("auth", "game", "lobby", "matchmaking", "api-gateway", "socket-a", "socket-b", "dispatcher")
    bases = {name: "http://127.0.0.1:" + str(smoke.port()) for name in names}
    processes, logs = [], []
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")

    def wait_health(url, process):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise AssertionError("Process stopped; logs: " + str(directory))
            try:
                if exchange(url + "/healthz")[0] == 200:
                    return
            except urllib.error.URLError:
                pass
            time.sleep(0.1)
        raise AssertionError("Health timeout; logs: " + str(directory))

    def start(name):
        service = "realtime" if name in ("socket-a", "socket-b", "dispatcher") else name
        env = smoke.local.environment(service)
        env.update({"PORT": bases[name].rsplit(":", 1)[1], "SERVER_ADDRESS": "127.0.0.1",
                    "AUTH_URL": bases["auth"], "GAME_URL": bases["game"], "LOBBY_URL": bases["lobby"],
                    "MATCHMAKING_URL": bases["matchmaking"], "MATCHMAKING_RETRY_MS": "3600000",
                    "REALTIME_POD_ID": name + "-" + str(len(processes)), "REALTIME_ROLE": "dispatcher" if name == "dispatcher" else "socket"})
        if service in ("matchmaking", "realtime"):
            command = [str(ROOT / "services" / service / "build" / service)]
        else:
            jar = next(p for p in (ROOT / "services" / service / "build/libs").glob("*.jar")
                       if not p.name.endswith("-plain.jar"))
            command = [java, "-jar", str(jar)]
        log = (directory / (name + "-" + str(len(processes)) + ".log")).open("w")
        logs.append(log)
        process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        if service in ("matchmaking", "realtime"):
            wait_health(bases[name], process)
        else:
            smoke.wait_health(bases[name], process)
        return process

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

    try:
        start("auth")
        game = start("game")
        for name in ("lobby", "matchmaking", "api-gateway"):
            start(name)
        first = start("socket-a")
        start("socket-b")
        start("dispatcher")
        env = {**os.environ, "PROBE_GATEWAY": bases["api-gateway"],
               "PROBE_SOCKET_0": bases["socket-a"].replace("http:", "ws:") + "/wss",
               "PROBE_SOCKET_1": bases["socket-b"].replace("http:", "ws:") + "/wss",
               "PROBE_KILL_SOCKET_PID": str(first.pid)}
        probe = subprocess.Popen([str(ROOT / "services/realtime/build/check-realtime")], env=env,
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        processes.append(probe)
        line = probe.stdout.readline()
        assert json.loads(line)["phase"] == "paused", "Probe failed; logs: " + str(directory)
        game.kill()
        game.wait()
        start("game")
        probe.stdin.write("continue\n")
        probe.stdin.flush()
        restored = probe.stdout.readline()
        assert json.loads(restored)["phase"] == "restored", "Game recovery failed; logs: " + str(directory)
        start("socket-a")
        probe.stdin.write("continue\n")
        probe.stdin.flush()
        output, _ = probe.communicate(timeout=30)
        assert probe.returncode == 0, "Probe failed; logs: " + str(directory)
        print(output.strip())
        print("Runtime logs:", directory)
    finally:
        for process in reversed(processes):
            stop(process)
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
