#!/usr/bin/env python3
"""Deploy the isolated local 14.1 profile to docker-desktop; never deletes data."""
import argparse
import base64
import http.client
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
from render import KUBE_ROOT, ROOT, render
from storage import render as stores

KUBECTL = ['kubectl', '--context', 'docker-desktop']


def kubectl(*args, **kwargs):
    return subprocess.run(KUBECTL + list(args), check=True, **kwargs)


def apply(items):
    kubectl('apply', '-f', '-', input=json.dumps({'apiVersion': 'v1', 'kind': 'List', 'items': items}), text=True)


def credential():
    folder = KUBE_ROOT / '.local'
    folder.mkdir(mode=0o700, exist_ok=True)
    path = folder / 'credential.json'
    if not path.exists():
        values = {name: secrets.token_urlsafe(32) for name in
                  ['postgres', 'redis', 'nats', 'auth', 'user', 'lobby', 'game', 'matchmaking', 'realtime']}
        values['tokens'] = {name: secrets.token_urlsafe(32) for name in ('auth', 'game', 'lobby', 'matchmaking')}
        values['loginKey'] = base64.b64encode(secrets.token_bytes(32)).decode()
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as output:
            json.dump(values, output)
    return json.loads(path.read_text())


def secret(name, namespace, values, kind='Opaque'):
    return {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': name, 'namespace': namespace},
            'type': kind, 'data': {key: base64.b64encode(value.encode() if isinstance(value, str) else value).decode()
                                  for key, value in values.items()}}


def wait_store(namespace, name, command):
    kubectl('-n', namespace, 'rollout', 'status', 'statefulset/' + name, '--timeout=180s')
    for _ in range(45):
        result = subprocess.run(KUBECTL + ['-n', namespace, 'exec', name + '-0', '--'] + command,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode == 0:
            return
        time.sleep(1)
    raise SystemExit(name + ' did not become ready')


def wait_application(namespace, name, port):
    # Rollout counts running containers before Spring has finished startup. Check
    # the actual internal health endpoint without publishing a cleartext listener.
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        local_port = reservation.getsockname()[1]
    log = (KUBE_ROOT / '.local' / ('health-' + name + '.log')).open('w')
    process = subprocess.Popen(KUBECTL + ['-n', namespace, 'port-forward', '--address=127.0.0.1',
        'service/' + name, str(local_port) + ':' + str(port)], stdout=log, stderr=log)
    try:
        for _ in range(90):
            if process.poll() is not None:
                process = subprocess.Popen(KUBECTL + ['-n', namespace, 'port-forward', '--address=127.0.0.1',
                    'service/' + name, str(local_port) + ':' + str(port)], stdout=log, stderr=log)
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--renew-certificate', action='store_true', help='Reissue the local ingress leaf using its existing key')
    args = parser.parse_args()
    v = json.loads((KUBE_ROOT / 'values.json').read_text())
    app, ingress, data = (v[key] for key in ('namespace', 'ingressNamespace', 'dataNamespace'))
    c = credential()
    platform, storage = render(v), stores(v)
    # Namespace, isolation, config and routes precede all workloads.
    apply([item for item in platform if item['kind'] != 'Deployment'])
    items = [secret('postgres-credential', data, {'POSTGRES_PASSWORD': c['postgres'],
        **{key.upper() + '_DB_PASSWORD': c[key] for key in ('auth', 'user', 'lobby', 'game', 'matchmaking', 'realtime')}}),
        secret('redis-credential', data, {'REDIS_PASSWORD': c['redis']}),
        secret('nats-credential', data, {'NATS_PASSWORD': c['nats']})]
    for name in ('auth', 'user', 'lobby', 'game', 'matchmaking', 'realtime', 'redis', 'nats'):
        items.append(secret(name + '-credential', app, {'password': c[name]}))
    for name, token in c['tokens'].items():
        items.append(secret(name + '-service-credential', app, {'token': token}))
    items.append(secret('login-key', app, {'login-keys.properties': 'cluster-v1=' + c['loginKey'] + '\n'}))
    certs = ROOT / 'infra/local/certs'
    # Dedicated cluster certificate; the CA remains on the host, never in Kubernetes.
    key, cert = KUBE_ROOT / '.local/ingress.key', KUBE_ROOT / '.local/ingress.crt'
    if key.exists() != cert.exists():
        raise SystemExit('Incomplete ingress certificate; preserve and repair existing files.')
    if not key.exists() or args.renew_certificate:
        extension = KUBE_ROOT / '.local/ingress.ext'
        extension.write_text('subjectAltName=IP:127.0.0.1,DNS:tictactoe.test\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\nextendedKeyUsage=serverAuth\n')
        old = os.umask(0o077)
        try:
            key_arguments = ['-key', str(key)] if key.exists() else ['-newkey', 'rsa:2048', '-nodes', '-keyout', str(key)]
            for command in [
                ['openssl', 'req', '-new', *key_arguments, '-out', str(KUBE_ROOT / '.local/ingress.csr'), '-subj', '/CN=tictactoe.test'],
                ['openssl', 'x509', '-req', '-in', str(KUBE_ROOT / '.local/ingress.csr'), '-CA', str(certs / 'ca.crt'), '-CAkey', str(certs / 'ca.key'), '-set_serial', '0x' + secrets.token_hex(16), '-out', str(cert), '-days', '180', '-extfile', str(extension)],
            ]:
                subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        finally:
            os.umask(old)
    subprocess.run(['openssl', 'verify', '-x509_strict', '-CAfile', str(certs / 'ca.crt'), str(cert)], check=True)
    items.append(secret('ingress-tls', ingress, {'tls.crt': cert.read_bytes(), 'tls.key': key.read_bytes()}, 'kubernetes.io/tls'))
    apply(items)
    apply([item for item in storage if item['kind'] != 'Job'])
    wait_store(data, 'postgres', ['pg_isready', '-U', 'postgres'])
    wait_store(data, 'nats', ['wget', '-q', '-O', '/dev/null', 'http://127.0.0.1:8222/healthz'])
    wait_store(data, 'redis', ['sh', '-ec', 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'])
    module_spec = importlib.util.spec_from_file_location('migration', ROOT / 'scripts/migrate.py')
    migration = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(migration)
    for name in migration.SERVICES:
        sql = migration.render(ROOT / 'services' / name / 'db/migration')
        kubectl('-n', data, 'exec', '-i', 'postgres-0', '--', 'bash', '-ec',
                'password_var="${1^^}_DB_PASSWORD"; export PGPASSWORD="${!password_var}"; '
                'exec psql -X -h 127.0.0.1 -U "$1" -d "$1" -v ON_ERROR_STOP=1',
                'migrate', name, input=sql, text=True, stdout=subprocess.DEVNULL)
        print('Migrated:', name, flush=True)
    apply([item for item in storage if item['kind'] == 'Job'])
    kubectl('-n', data, 'wait', '--for=condition=complete', 'job/bootstrap-stream', '--timeout=120s')
    apply([item for item in platform if item['kind'] == 'Deployment'])
    for item in platform:
        if item['kind'] == 'Deployment':
            kubectl('-n', item['metadata']['namespace'], 'rollout', 'status',
                    'deployment/' + item['metadata']['name'], '--timeout=240s')
    from render import SERVICE_PORT
    for name, port in SERVICE_PORT.items():
        wait_application(app, name, port)
    print('Deployed local profile; run smoke.py for acceptance checks.')


if __name__ == '__main__':
    main()
