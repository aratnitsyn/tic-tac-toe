#!/usr/bin/env python3
"""Own-process backend for browser integration; creates/retains only test data."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('smoke', root / 'scripts/check-auth-runtime.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
folder = Path(tempfile.mkdtemp(prefix='ttt-web-test-'))
processes, logs = [], []
names = ('auth', 'game', 'lobby', 'matchmaking', 'api-gateway', 'realtime')
bases = {name: 'http://127.0.0.1:' + str(smoke.port()) for name in names}


def stop(*_):
    for child in reversed(processes):
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
    for log in logs:
        log.close()


def interrupt(*_):
    raise SystemExit(0)


signal.signal(signal.SIGTERM, interrupt)
signal.signal(signal.SIGINT, interrupt)
try:
    for name in names:
        env = smoke.local.environment(name)
        env.update({'PORT': bases[name].rsplit(':', 1)[1], 'SERVER_ADDRESS': '127.0.0.1',
                    'AUTH_URL': bases['auth'], 'GAME_URL': bases['game'], 'LOBBY_URL': bases['lobby'],
                    'MATCHMAKING_URL': bases['matchmaking'], 'WEB_ORIGIN': 'https://127.0.0.1:3000',
                    'WEB_ORIGINS': 'https://127.0.0.1:3000', 'REALTIME_POD_ID': 'web-' + os.urandom(8).hex(),
                    'REALTIME_ROLE': 'all', 'MATCHMAKING_RETRY_MS': '100', 'LOBBY_RETRY_MS': '100'})
        if name in ('matchmaking', 'realtime'):
            command = [str(root / 'services' / name / 'build' / name)]
        else:
            java = Path(os.environ['JAVA_HOME']) / 'bin/java'
            jar = next(p for p in (root / 'services' / name / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar'))
            command = [str(java), '-jar', str(jar)]
        log = (folder / (name + '.log')).open('w')
        logs.append(log)
        child = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(child)
        if name in ('matchmaking', 'realtime'):
            import urllib.request
            import time
            for attempt in range(200):
                if child.poll() is not None:
                    raise SystemExit('Backend stopped; logs: ' + str(folder))
                try:
                    urllib.request.urlopen(bases[name] + '/healthz', timeout=1).close()
                    break
                except OSError:
                    time.sleep(.1)
            else:
                raise SystemExit('Backend health timeout; logs: ' + str(folder))
        else:
            smoke.wait_health(bases[name], child)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            data = json.dumps({'gateway': bases['api-gateway'], 'socket': bases['realtime'].replace('http:', 'ws:') + '/wss'}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 19090), Handler)
    print('Web test backend ready; logs: ' + str(folder), flush=True)
    server.serve_forever()
finally:
    stop()
