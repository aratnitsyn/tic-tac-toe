#!/usr/bin/env python3
"""Install/adopt local Helm releases; preserve PVCs and require Vault synchronization."""
import importlib.util
import http.client
import socket
import subprocess
import time
from common import KUBE_ROOT, ROOT, SERVICE_PORT, KUBECTL, helm, kubectl, values


def wait_store(namespace, name, command):
    kubectl('-n', namespace, 'rollout', 'status', 'statefulset/' + name, '--timeout=180s')
    for _ in range(45):
        result = subprocess.run(KUBECTL + ['-n', namespace, 'exec', name + '-0', '--'] + command,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode == 0:
            return
        time.sleep(1)
    raise SystemExit(name + ' did not become ready')


def wait_application(namespace, name, port, target="service/"):
    # Rollout counts running containers before Spring has finished startup. Check
    # the actual internal health endpoint without publishing a cleartext listener.
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        local_port = reservation.getsockname()[1]
    log = (KUBE_ROOT / '.local' / ('health-' + name + '.log')).open('w')
    process = subprocess.Popen(KUBECTL + ['-n', namespace, 'port-forward', '--address=127.0.0.1',
        target + name, str(local_port) + ':' + str(port)], stdout=log, stderr=log)
    try:
        for _ in range(300):
            if process.poll() is not None:
                process = subprocess.Popen(KUBECTL + ['-n', namespace, 'port-forward', '--address=127.0.0.1',
                    target + name, str(local_port) + ':' + str(port)], stdout=log, stderr=log)
            client = http.client.HTTPConnection('127.0.0.1', local_port, timeout=2)
            try:
                client.request('GET', '/healthz' if name in ('matchmaking', 'realtime', 'realtime-dispatcher') else '/actuator/health')
                response = client.getresponse()
                response.read()
                if response.status == 200:
                    print('Healthy:', name, flush=True)
                    return
            except (OSError, http.client.HTTPException):
                pass
            finally:
                client.close()
            time.sleep(1)
        raise SystemExit('Application health timeout: ' + name)
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        log.close()


def upgrade(name, namespace):
    helm('upgrade', '--install', name, str(KUBE_ROOT / 'chart' / name), '-n', namespace,
         '-f', str(KUBE_ROOT / 'values.yaml'), '--take-ownership', '--wait', '--timeout', '300s')


def main():
    v = values()
    # Vault sync must already be healthy. Never mint replacement credentials here.
    for item in v['secret']:
        kubectl('-n', item['namespace'], 'wait', '--for=condition=SecretSynced',
                'vaultstaticsecret/' + item['name'], '--timeout=30s')
    upgrade('storage', v['dataNamespace'])
    for name, command in [('postgres', ['pg_isready', '-U', 'postgres']),
                          ('nats', ['wget', '-q', '-O', '/dev/null', 'http://127.0.0.1:8222/healthz']),
                          ('redis', ['sh', '-ec', 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'])]:
        wait_store(v['dataNamespace'], name, command)
    module_spec = importlib.util.spec_from_file_location('migration', ROOT / 'scripts/migrate.py')
    migration = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(migration)
    for name in migration.SERVICES:
        sql = migration.render(ROOT / 'services' / name / 'db/migration')
        kubectl('-n', v['dataNamespace'], 'exec', '-i', 'postgres-0', '--', 'bash', '-ec',
                'password_var="${1^^}_DB_PASSWORD"; export PGPASSWORD="${!password_var}"; '
                'exec psql -X -h 127.0.0.1 -U "$1" -d "$1" -v ON_ERROR_STOP=1',
                'migrate', name, input=sql, text=True, stdout=subprocess.DEVNULL)
        print('Migrated:', name, flush=True)
    upgrade('mesh', v['namespace'])
    upgrade('platform', v['namespace'])
    for name, port in SERVICE_PORT.items():
        wait_application(v['namespace'], name, port)
    print('Helm releases deployed; Vault remains the secret source. Run smoke.py.')


if __name__ == '__main__':
    main()
