#!/usr/bin/env python3
"""Verify a real ConfigMap-driven port change and Helm rollback on the local gateway."""
import json
from common import KUBE_ROOT, helm, kubectl, values
from deploy import wait_application


def account_fingerprint(namespace):
    result = kubectl('-n', namespace, 'exec', 'postgres-0', '--', 'bash', '-ec',
                     'export PGPASSWORD="$AUTH_DB_PASSWORD"; exec psql -X -h 127.0.0.1 -U auth -d auth -At -v ON_ERROR_STOP=1 -c "$1"',
                     'verify', "SELECT count(*)::text || ':' || md5(coalesce(string_agg(user_id::text, ',' ORDER BY user_id), '')) FROM accounts", capture_output=True, text=True)
    return result.stdout.strip()


def main():
    profile = values()
    namespace = profile['namespace']
    before = account_fingerprint(profile['dataNamespace'])
    current = json.loads(helm('status', 'platform', '-n', namespace, '-o', 'json', capture_output=True, text=True).stdout)['version']
    try:
        helm('upgrade', 'platform', str(KUBE_ROOT/'chart/platform'), '-n', namespace,
             '-f', str(KUBE_ROOT/'values.yaml'), '--set-string', 'config.api-gateway.PORT=8090',
             '--wait', '--timeout', '180s')
        config = json.loads(kubectl('-n', namespace, 'get', 'configmap', 'api-gateway', '-o', 'json', capture_output=True, text=True).stdout)
        if config['data']['PORT'] != '8090':raise RuntimeError('ConfigMap port update was not applied')
        wait_application(namespace, 'api-gateway', 8090, 'deployment/')
        print('PASS: Helm values updated ConfigMap and the application serves the configured port')
    finally:
        helm('rollback', 'platform', str(current), '-n', namespace, '--wait', '--timeout', '180s')
    wait_application(namespace, 'api-gateway', 8080)
    if account_fingerprint(profile['dataNamespace']) != before:
        raise RuntimeError('Account identities changed during Helm upgrade/rollback')
    print('PASS: compatible Helm rollback restored the original application port and preserved account identities')


if __name__ == '__main__':
    main()
