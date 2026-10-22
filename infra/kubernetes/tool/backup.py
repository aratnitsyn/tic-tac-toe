#!/usr/bin/env python3
"""Encrypted, authenticated local backup and isolated restore drill for all durable stores."""
import argparse
import base64
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import hmac
import http.client
import io
import json
import os
from pathlib import Path
import secrets
import shlex
import socket
import ssl
import subprocess
import tarfile
import time
import uuid
import yaml
from common import KUBE_ROOT, ROOT, KUBECTL, helm, kubectl, values
from vault import connection, keychain_get, VAULT_HOST

KEY_SERVICE = 'com.github.aratnitsyn.tictactoe.backup.docker-desktop'
DATABASE = ('auth', 'user', 'lobby', 'game', 'matchmaking', 'realtime')
MAGIC = b'TTTBACKUP1\n'


def key(create=False):
    result = subprocess.run(['security', 'find-generic-password', '-s', KEY_SERVICE, '-a', 'encryption-v1', '-w'], capture_output=True, text=True)
    if result.returncode == 44 and create:
        value = secrets.token_hex(32)
        command = 'add-generic-password -s '+shlex.quote(KEY_SERVICE)+' -a encryption-v1 -w '+shlex.quote(value)
        subprocess.run(['security', '-i'], input=command+'\n', capture_output=True, text=True, check=True)
        return key()
    if result.returncode:
        raise RuntimeError('Backup key unavailable in macOS Keychain; never replace it for an existing backup')
    return bytes.fromhex(result.stdout.strip())


def cipher(data, master, decrypt=False):
    # Passphrase goes through a private FD, never argv, environment or a file.
    read_fd, write_fd = os.pipe()
    try:
        password = hmac.digest(master, b'encryption-v1', 'sha256').hex().encode()+b'\n'
        os.write(write_fd, password)
        os.close(write_fd)
        write_fd = None
        result = subprocess.run(['openssl', 'enc', '-aes-256-cbc', '-pbkdf2', '-iter', '200000',
                                 '-pass', 'fd:'+str(read_fd)] + (['-d'] if decrypt else ['-salt']),
                                input=data, capture_output=True, pass_fds=(read_fd,))
        if result.returncode:
            raise RuntimeError('Backup encryption/decryption failed')
        return result.stdout
    finally:
        os.close(read_fd)
        if write_fd is not None:
            os.close(write_fd)


def encode(data, master):
    encrypted = cipher(data, master)
    mac_key = hmac.digest(master, b'authentication-v1', 'sha256')
    return MAGIC+hmac.digest(mac_key, MAGIC+encrypted, 'sha256')+encrypted


def decode(data, master):
    if not data.startswith(MAGIC) or len(data) < len(MAGIC)+48:
        raise RuntimeError('Invalid backup format')
    signature, encrypted = data[len(MAGIC):len(MAGIC)+32], data[len(MAGIC)+32:]
    expected = hmac.digest(hmac.digest(master, b'authentication-v1', 'sha256'), MAGIC+encrypted, 'sha256')
    if not hmac.compare_digest(signature, expected):
        raise RuntimeError('Backup authentication failed; refusing decryption/restore')
    return cipher(encrypted, master, decrypt=True)


def execute(namespace, pod, *command, data=None):
    result = kubectl('-n', namespace, 'exec', '-i', pod, '--', *command,
                     input=data, capture_output=True)
    return result.stdout


def sql(namespace, pod, database, query, admin='postgres'):
    return execute(namespace, pod, 'psql', '-XAt', '-U', admin, '-d', database,
                   '-v', 'ON_ERROR_STOP=1', '-c', query).decode().strip()


def fingerprint(namespace, pod, admin='postgres'):
    result = {}
    for database in DATABASE:
        tables = sql(namespace, pod, database,
                     "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", admin).splitlines()
        for table in tables:
            quoted = '"'+table.replace('"', '""')+'"'
            result[database+'.'+table] = sql(namespace, pod, database,
                "SELECT count(*)::text||':'||md5(coalesce(string_agg(to_jsonb(t)::text,'' ORDER BY to_jsonb(t)::text),'')) FROM "+quoted+' t', admin)
    return result


def nats_state(namespace, pod):
    body = execute(namespace, pod, 'wget', '-qO-', 'http://127.0.0.1:8222/jsz?accounts=true&streams=true&consumers=true')
    result = {}
    for account in json.loads(body).get('account_details', []):
        for stream in account.get('stream_detail', []):
            result[stream['name']] = stream.get('state', {})
    return result


def tar(files):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as archive:
        for name, data in files.items():
            item = tarfile.TarInfo(name)
            item.size, item.mode = len(data), 0o600
            archive.addfile(item, io.BytesIO(data))
    result = output.getvalue()
    safe_archive(result)
    return result


def safe_archive(data):
    # Validate backups before sending tar to any restore container.
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for item in archive:
            if item.name.startswith('/') or '..' in Path(item.name).parts or not (item.isfile() or item.isdir()):
                raise RuntimeError('Unsafe file in store archive')


def create(output):
    profile = values()
    namespace, data_namespace = profile['namespace'], profile['dataNamespace']
    deployments = json.loads(kubectl('-n', namespace, 'get', 'deployment', '-o', 'json', capture_output=True, text=True).stdout)['items']
    replicas = {d['metadata']['name']:d['spec'].get('replicas', 1) for d in deployments}
    recovery = keychain_get()
    master = key(create=True)
    payload = {'version':1, 'createdAt':datetime.now(timezone.utc).isoformat(), 'file':{}, 'replicas':replicas}
    def put(name, value):
        payload['file'][name] = base64.b64encode(value).decode()
    try:
        kubectl('-n', namespace, 'scale', 'deployment', '--all', '--replicas=0')
        kubectl('-n', namespace, 'wait', '--for=delete', 'pod', '--all', '--timeout=90s')
        payload['snapshotAt'] = datetime.now(timezone.utc).isoformat()
        payload['fingerprint'] = fingerprint(data_namespace, 'postgres-0')
        put('postgres.sql', execute(data_namespace, 'postgres-0', 'pg_dumpall', '-U', 'postgres', '--clean', '--if-exists'))
        payload['natsState'] = nats_state(data_namespace, 'nats-0')
        if not payload['natsState'] or not payload['fingerprint']:
            raise RuntimeError('Expected existing stream/table data for a meaningful backup drill')
        try:
            execute(data_namespace, 'nats-0', 'kill', '-STOP', '1')
            put('nats.tar', execute(data_namespace, 'nats-0', 'tar', '-C', '/data', '-cf', '-', '.'))
        finally:
            execute(data_namespace, 'nats-0', 'kill', '-CONT', '1')
        execute(data_namespace, 'redis-0', 'sh', '-ec', 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli SAVE')
        put('redis.rdb', execute(data_namespace, 'redis-0', 'cat', '/data/dump.rdb'))
        with connection() as api:
            tls = api('platform/data/tictactoe-vault/vault-tls', token=recovery['root_token'])['data']['data']
            payload['vaultSecretHash'] = hashlib.sha256(json.dumps(api('platform/data/tictactoe/login-key', token=recovery['root_token'])['data']['data'], sort_keys=True).encode()).hexdigest()
            for name, value in tls.items():
                put('tls/'+name, value.encode())
            try:
                api('sys/seal', {}, recovery['root_token'])
                put('vault.tar', execute('tictactoe-vault', 'vault-0', 'tar', '-C', '/vault', '-cf', '-', 'data'))
            finally:
                if api('sys/seal-status')['sealed']:
                    for share in recovery['keys_base64'][:2]:
                        api('sys/unseal', {'key':share})
        payload['completedAt'] = datetime.now(timezone.utc).isoformat()
        output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with output.open('xb') as stream:
            os.chmod(output, 0o600)
            stream.write(encode(json.dumps(payload).encode(), master))
        print('Encrypted authenticated backup saved: '+str(output), flush=True)
    finally:
        for name, count in replicas.items():
            kubectl('-n', namespace, 'scale', 'deployment/'+name, '--replicas='+str(count))
        for name, count in replicas.items():
            if count:
                kubectl('-n', namespace, 'rollout', 'status', 'deployment/'+name, '--timeout=180s')


@contextmanager
def restored_vault(namespace):
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0))
        port = reservation.getsockname()[1]
    process = subprocess.Popen(KUBECTL+['-n',namespace,'port-forward','--address=127.0.0.1','pod/vault',str(port)+':8200'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    context = ssl.create_default_context(cafile=str(ROOT/'infra/local/certs/ca.crt'))
    def api(path, body=None, token=None):
        client = http.client.HTTPSConnection(VAULT_HOST, port, context=context, timeout=5)
        client._create_connection = lambda address, timeout, source_address=None: socket.create_connection(('127.0.0.1',port), timeout)
        try:
            client.request('POST' if body is not None else 'GET', '/v1/'+path,
                           json.dumps(body) if body is not None else None,
                           {'X-Vault-Token':token} if token else {})
            response = client.getresponse()
            raw = response.read()
            if response.status >= 400:
                raise RuntimeError('Restored Vault HTTP '+str(response.status))
            return json.loads(raw) if raw else {}
        finally:
            client.close()
    try:
        for _ in range(60):
            try:
                api('sys/init')
                break
            except (OSError, http.client.HTTPException):
                time.sleep(1)
        else:
            raise RuntimeError('Restored Vault TLS endpoint unavailable')
        yield api
    finally:
        process.terminate()
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:process.kill();process.wait()


def restore_check(path):
    # Authentication is verified before creating any Kubernetes resources.
    payload = json.loads(decode(path.read_bytes(), key()))
    if payload.get('version') != 1:
        raise RuntimeError('Unknown backup version')
    files = {name:base64.b64decode(data, validate=True) for name,data in payload['file'].items()}
    for name in ('vault.tar','nats.tar'):
        safe_archive(files[name])
    namespace = 'tictactoe-recovery-'+uuid.uuid4().hex[:10]
    started = time.monotonic()
    namespace_created = False
    try:
        rendered = helm('template', 'recovery', str(KUBE_ROOT/'chart/recovery'),
                        '--set-string', 'namespace='+namespace, capture_output=True, text=True)
        namespaces = [item for item in yaml.safe_load_all(rendered.stdout) if item and item['kind']=='Namespace']
        kubectl('create', '-f', '-', input=yaml.safe_dump_all(namespaces), text=True)
        namespace_created = True
        helm('install', 'recovery', str(KUBE_ROOT/'chart/recovery'), '-n', namespace,
             '--take-ownership', '--set-string', 'namespace='+namespace)
        kubectl('-n', namespace, 'wait', '--for=jsonpath={.status.phase}=Running', 'pod', '--all', '--timeout=120s')
        for _ in range(60):
            result = subprocess.run(KUBECTL+['-n',namespace,'exec','postgres','--','pg_isready','-U','recovery_admin'], capture_output=True)
            if result.returncode == 0:break
            time.sleep(1)
        else:raise RuntimeError('Clean PostgreSQL did not start')
        execute(namespace,'postgres','psql','-X','-U','recovery_admin','-d','recovery_admin','-v','ON_ERROR_STOP=1',data=files['postgres.sql'])
        if fingerprint(namespace,'postgres','recovery_admin') != payload['fingerprint']:
            raise RuntimeError('Restored database records differ from source backup')
        print('PASS: all public tables in six databases restored with identical count/content hashes', flush=True)
        execute(namespace,'vault','tar','-C','/work','-xf','-',data=files['vault.tar'])
        tls = {name:content for name,content in files.items() if name.startswith('tls/')}
        tls['server.hcl'] = b'disable_mlock = true\nlistener "tcp" { address = "0.0.0.0:8200"\n tls_cert_file = "/work/tls/tls.crt"\n tls_key_file = "/work/tls/tls.key"\n}\nstorage "file" { path = "/work/data" }\n'
        tls['start'] = b''
        execute(namespace,'vault','tar','-C','/work','-xf','-',data=tar(tls))
        recovery = keychain_get()
        with restored_vault(namespace) as api:
            if not api('sys/init')['initialized']:
                raise RuntimeError('Vault backup missing initialized storage')
            for share in recovery['keys_base64'][:2]:api('sys/unseal',{'key':share})
            restored = api('platform/data/tictactoe/login-key',token=recovery['root_token'])['data']['data']
            if hashlib.sha256(json.dumps(restored,sort_keys=True).encode()).hexdigest() != payload['vaultSecretHash']:
                raise RuntimeError('Restored login keys differ')
        print('PASS: clean Vault restored and unsealed with separate Keychain recovery; login key retained',flush=True)
        execute(namespace,'nats','tar','-C','/work','-xf','-',data=files['nats.tar'])
        execute(namespace,'nats','tar','-C','/work','-xf','-',data=tar({'server.conf':b'host: 127.0.0.1\nhttp: 127.0.0.1:8222\njetstream { store_dir: "/work" }\n','start':b''}))
        for _ in range(30):
            try:
                if nats_state(namespace,'nats') != payload['natsState']:
                    raise RuntimeError('Restored JetStream state differs')
                break
            except subprocess.CalledProcessError:time.sleep(1)
        else:raise RuntimeError('Restored NATS unavailable')
        print('PASS: JetStream stream state restored',flush=True)
        execute(namespace,'redis','tar','-C','/work','-xf','-',data=tar({'dump.rdb':files['redis.rdb'],'server.conf':b'bind 127.0.0.1\nport 6379\ndir /work\ndbfilename dump.rdb\n','start':b''}))
        for _ in range(30):
            try:
                if execute(namespace,'redis','redis-cli','ping').strip() == b'PONG':break
            except subprocess.CalledProcessError:pass
            time.sleep(1)
        else:raise RuntimeError('Restored Redis unavailable')
        report={'backup':path.name,'tables':len(payload['fingerprint']),
                'snapshotAt':payload['snapshotAt'],'verifiedAt':datetime.now(timezone.utc).isoformat(),
                'restoreSeconds':round(time.monotonic()-started,3),
                'backupAgeSeconds':round((datetime.now(timezone.utc)-datetime.fromisoformat(payload['snapshotAt'])).total_seconds(),3),
                'scope':'isolated store restore; not production traffic recovery; backup age is potential RPO, not a zero-loss guarantee'}
        report_path=KUBE_ROOT/'.local/restore-report.json'
        report_path.write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps(report),flush=True)
    finally:
        # Only the newly generated namespace can be removed by this command.
        if namespace_created:
            kubectl('delete','namespace',namespace,'--ignore-not-found','--wait=true','--timeout=90s')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation',choices=['create','restore-check'])
    parser.add_argument('--file',type=Path)
    args=parser.parse_args()
    if args.operation=='restore-check' and not args.file:parser.error('--file is required for restore-check')
    path=args.file or KUBE_ROOT/'.local/backup'/('backup-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'.tttbak')
    if args.operation=='create':create(path.resolve())
    else:restore_check(path.resolve())


if __name__=='__main__':main()
