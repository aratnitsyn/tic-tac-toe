#!/usr/bin/env python3
"""Verify Vault outage blocks a new secret consumer and recover using Keychain."""
import json
import time
from common import apply, kubectl
from vault import connection, keychain_get


def main():
    namespace, name = 'tictactoe', 'vault-availability-probe'
    recovery = keychain_get()
    with connection() as api:
        try:
            api('sys/seal', {}, recovery['root_token'])
            apply([{'apiVersion':'secrets.hashicorp.com/v1beta1','kind':'VaultStaticSecret',
                    'metadata':{'name':name,'namespace':namespace},
                    'spec':{'vaultAuthRef':'auth-credential','mount':'platform','type':'kv-v2',
                            'path':'tictactoe/auth-credential','refreshAfter':'5s',
                            'destination':{'name':name,'create':True,'transformation':{'excludeRaw':True}}}},
                   {'apiVersion':'v1','kind':'Pod','metadata':{'name':name,'namespace':namespace,
                     'annotations':{'sidecar.istio.io/inject':'false'}},
                    'spec':{'automountServiceAccountToken':False,'restartPolicy':'Never',
                            'containers':[{'name':'probe','image':'python:3.13.7-alpine',
                             'command':['python','-c','import time; time.sleep(300)'],
                             'env':[{'name':'REQUIRED_SECRET','valueFrom':{'secretKeyRef':{'name':name,'key':'password'}}}]}]}}])
            blocked = False
            for _ in range(30):
                pod = json.loads(kubectl('-n',namespace,'get','pod',name,'-o','json',capture_output=True,text=True).stdout)
                states = pod['status'].get('containerStatuses',[])
                if any(s.get('state',{}).get('waiting',{}).get('reason')=='CreateContainerConfigError' for s in states):
                    blocked = True; break
                time.sleep(1)
            if not blocked:raise RuntimeError('New workload did not block on missing secret during Vault outage')
            print('PASS: new consumer cannot start while required Vault secret is unavailable')
        finally:
            if api('sys/seal-status')['sealed']:
                for key in recovery['keys_base64'][:2]:api('sys/unseal',{'key':key})
        kubectl('-n',namespace,'wait','--for=condition=SecretSynced','vaultstaticsecret/'+name,'--timeout=90s')
        kubectl('-n',namespace,'wait','--for=condition=Ready','pod/'+name,'--timeout=90s')
        kubectl('-n',namespace,'delete','pod',name,'--wait=true')
        kubectl('-n',namespace,'delete','vaultstaticsecret',name,'--wait=true')
        # VSO normally garbage-collects its owned destination; remove only this probe if it remains.
        kubectl('-n',namespace,'delete','secret',name,'--ignore-not-found=true')
        print('PASS: Keychain recovery unsealed Vault; VSO synchronized and consumer started; probe removed')


if __name__ == '__main__':
    main()
