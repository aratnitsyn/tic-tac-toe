#!/usr/bin/env python3
"""Check actual Vault namespace policies and rotation propagation without printing data."""
import argparse
import base64
import json
import secrets
import time
import os
from pathlib import Path
import subprocess
import tempfile
from common import kubectl
from vault import connection, get_secret, keychain_get


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rotate-service-token', action='store_true')
    parser.add_argument('--rotate-ingress-certificate', action='store_true')
    args = parser.parse_args()
    with connection() as api:
        jwt = kubectl('-n', 'tictactoe', 'create', 'token', 'sync-auth-credential',
                      '--audience=vault', '--duration=10m', capture_output=True, text=True).stdout.strip()
        login = api('auth/kubernetes/login', {'role': 'tictactoe-auth-credential', 'jwt': jwt})
        token = login['auth']['client_token']
        api('platform/data/tictactoe/auth-credential', token=token)
        try:
            api('platform/data/tictactoe/game-credential', token=token)
        except RuntimeError as error:
            if not str(error).endswith(('HTTP 400', 'HTTP 403')): raise
        else: raise RuntimeError('Vault policy allowed another service secret')
        try:
            api('auth/kubernetes/login', {'role': 'tictactoe-data-postgres-credential', 'jwt': jwt})
        except RuntimeError as error:
            if not str(error).endswith(('HTTP 400', 'HTTP 403')): raise
        else: raise RuntimeError('Vault role accepted another namespace ServiceAccount')
        api('auth/token/revoke-self', {}, token)
        print('PASS: own path allowed; foreign path/namespace denied; scoped token revoked')
        if args.rotate_service_token:
            root = keychain_get()['root_token']
            path = 'platform/data/tictactoe/auth-service-credential'
            current = api(path, token=root)['data']
            new = secrets.token_urlsafe(32)
            api(path, {'options': {'cas': current['metadata']['version']}, 'data': {'token': new}}, root)
            for _ in range(60):
                if get_secret('tictactoe', 'auth-service-credential')['token'] == new:
                    break
                time.sleep(1)
            else: raise RuntimeError('VSO did not synchronize service token rotation')
            print('PASS: service token rotated in Vault and synchronized by VSO')
        if args.rotate_ingress_certificate:
            root = keychain_get()['root_token']
            ca = api('platform/data/authority/local-ca', token=root)['data']['data']
            path = 'platform/data/tictactoe-ingress/ingress-tls'
            current = api(path, token=root)['data']
            data = dict(current['data'])
            with tempfile.TemporaryDirectory() as folder:
                tmp = Path(folder)
                os.chmod(tmp, 0o700)
                for name, value in [('key', data['tls.key']), ('ca.key', ca['tls.key']), ('ca.crt', ca['tls.crt'])]:
                    (tmp/name).write_text(value); (tmp/name).chmod(0o600)
                (tmp/'extensions').write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\nsubjectAltName=DNS:tictactoe.test,IP:127.0.0.1\n')
                for command in [
                    ['openssl','req','-new','-key',str(tmp/'key'),'-out',str(tmp/'csr'),'-subj','/CN=tictactoe.test'],
                    ['openssl','x509','-req','-in',str(tmp/'csr'),'-CA',str(tmp/'ca.crt'),'-CAkey',str(tmp/'ca.key'),'-set_serial','0x'+os.urandom(16).hex(),'-days','180','-extfile',str(tmp/'extensions'),'-out',str(tmp/'cert')],
                    ['openssl','verify','-x509_strict','-CAfile',str(tmp/'ca.crt'),str(tmp/'cert')],
                ]:
                    subprocess.run(command, check=True, capture_output=True)
                data['tls.crt'] = (tmp/'cert').read_text()
            api(path, {'options': {'cas': current['metadata']['version']}, 'data': data}, root)
            for _ in range(60):
                if get_secret('tictactoe-ingress','ingress-tls')['tls.crt'] == data['tls.crt']:break
                time.sleep(1)
            else:raise RuntimeError('Ingress certificate rotation did not synchronize')
            print('PASS: new ingress certificate issued from Vault materials and synchronized by VSO')



if __name__ == '__main__':
    main()
