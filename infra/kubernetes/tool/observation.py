#!/usr/bin/env python3
"""Install private observation resources; generate Grafana credential only in Vault."""
import secrets
from common import KUBE_ROOT, apply, documents, helm, kubectl, values
from vault import configure, connection, keychain_get, put_bootstrap_secret, vault_path_exists


def main():
    profile = values()
    namespace = profile['observation']['namespace']
    apply([item for item in documents('observation') if item['kind']=='Namespace'])
    # The missing required Secret keeps Grafana unready until VSO synchronizes it.
    helm('upgrade','--install','observation',str(KUBE_ROOT/'chart/observation'),'-n',namespace,
         '-f',str(KUBE_ROOT/'values.yaml'),'--take-ownership')
    recovery = keychain_get()
    with connection() as api:
        token = recovery['root_token']
        ca = api('platform/data/authority/local-ca', token=token)['data']['data']['tls.crt']
        put_bootstrap_secret(namespace,'vault-ca',{'ca.crt':ca})
        for path,data in [('grafana-admin',{'password':secrets.token_urlsafe(32)}),('vault-ca',{'ca.crt':ca})]:
            if not vault_path_exists(api,'platform/metadata/'+namespace+'/'+path,token):
                api('platform/data/'+namespace+'/'+path,{'options':{'cas':0},'data':data},token)
        configure(api,recovery)
    helm('upgrade','secret',str(KUBE_ROOT/'chart/secret'),'-n','tictactoe-vault','-f',str(KUBE_ROOT/'values.yaml'),'--wait','--timeout','180s')
    for name in ('grafana-admin','vault-ca'):
        kubectl('-n',namespace,'wait','--for=condition=SecretSynced','vaultstaticsecret/'+name,'--timeout=120s')
    for name in ('prometheus','jaeger','grafana'):
        kubectl('-n',namespace,'rollout','status','deployment/'+name,'--timeout=180s')
    print('Private Prometheus/Jaeger/Grafana deployed; Grafana credential sourced from Vault')


if __name__=='__main__':
    main()
