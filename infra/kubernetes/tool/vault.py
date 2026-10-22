#!/usr/bin/env python3
"""Bootstrap local Vault, keep recovery in macOS Keychain, migrate existing Secrets."""
import argparse
import base64
from contextlib import contextmanager
import http.client
import json
import os
from pathlib import Path
import shlex
import secrets
import socket
import ssl
import subprocess
import tempfile
import time
import yaml
from common import KUBE_ROOT, ROOT, apply, helm, kubectl, KUBECTL, values, documents

NAMESPACE = 'tictactoe-vault'
KEYCHAIN_SERVICE = 'com.github.aratnitsyn.tictactoe.vault.docker-desktop'
VAULT_HOST = 'vault.tictactoe-vault.svc.cluster.local'


def keychain_get():
    result = subprocess.run(['security', 'find-generic-password', '-s', KEYCHAIN_SERVICE,
                             '-a', 'bootstrap', '-w'], capture_output=True, text=True)
    if result.returncode == 44:
        return None
    if result.returncode:
        raise RuntimeError('Unable to read Vault recovery from macOS Keychain')
    return json.loads(result.stdout)


def keychain_put(value):
    # Feed the Keychain command through stdin: recovery never enters argv or logs.
    command = 'add-generic-password -U -s ' + shlex.quote(KEYCHAIN_SERVICE) + ' -a bootstrap -w ' + shlex.quote(json.dumps(value))
    result = subprocess.run(['security', '-i'], input=command + '\n', capture_output=True, text=True)
    if result.returncode or keychain_get() != value:
        raise RuntimeError('Vault recovery could not be saved and verified in Keychain')


def get_secret(namespace, name):
    result = kubectl('-n', namespace, 'get', 'secret', name, '-o', 'json', capture_output=True, text=True)
    return {k: base64.b64decode(v).decode() for k, v in json.loads(result.stdout)['data'].items()}


def put_bootstrap_secret(namespace, name, data):
    apply([{'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': name, 'namespace': namespace},
            'type': 'Opaque', 'stringData': data}])


def bootstrap_tls():
    existing = subprocess.run(KUBECTL + ['-n', NAMESPACE, 'get', 'secret', 'vault-tls'],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if existing.returncode == 0:
        return
    certs = ROOT / 'infra/local/certs'
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder)
        os.chmod(path, 0o700)
        (path / 'extensions').write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\nsubjectAltName=DNS:' + VAULT_HOST + ',DNS:vault,DNS:vault.tictactoe-vault.svc,DNS:vault-0.vault-internal,IP:127.0.0.1\n')
        commands = [
            ['openssl','req','-new','-newkey','rsa:2048','-nodes','-keyout',str(path/'key'),'-out',str(path/'csr'),'-subj','/CN='+VAULT_HOST],
            ['openssl','x509','-req','-in',str(path/'csr'),'-CA',str(certs/'ca.crt'),'-CAkey',str(certs/'ca.key'),'-set_serial','0x'+os.urandom(16).hex(),'-days','180','-extfile',str(path/'extensions'),'-out',str(path/'cert')],
        ]
        for command in commands:
            subprocess.run(command, check=True, capture_output=True)
        put_bootstrap_secret(NAMESPACE, 'vault-tls', {'tls.crt':(path/'cert').read_text(), 'tls.key':(path/'key').read_text(), 'ca.crt':(certs/'ca.crt').read_text()})


@contextmanager
def connection():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
    process = subprocess.Popen(KUBECTL + ['-n', NAMESPACE, 'port-forward','--address=127.0.0.1','pod/vault-0',str(port)+':8200'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    context = ssl.create_default_context(cafile=str(ROOT/'infra/local/certs/ca.crt'))
    def request(path, data=None, token=None, method=None):
        client = http.client.HTTPSConnection(VAULT_HOST, port, context=context, timeout=8)
        client._create_connection = lambda address, timeout, source_address=None: socket.create_connection(('127.0.0.1',port),timeout)
        headers = {'Content-Type':'application/json'}
        if token: headers['X-Vault-Token']=token
        try:
            client.request(method or ('POST' if data is not None else 'GET'),'/v1/'+path, json.dumps(data) if data is not None else None, headers)
            response=client.getresponse(); body=response.read()
            if response.status>=400: raise RuntimeError('Vault API '+path+' returned HTTP '+str(response.status))
            return json.loads(body) if body else {}
        finally: client.close()
    try:
        for _ in range(60):
            try:
                request('sys/init');break
            except (OSError,http.client.HTTPException):time.sleep(1)
        else: raise RuntimeError('Vault TLS endpoint did not become available')
        yield request
    finally:
        process.terminate()
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:process.kill();process.wait()


def configure(api, recovery, initialize_new=False):
    token=recovery['root_token']
    mounts=api('sys/mounts',token=token)
    if 'platform/' not in mounts:api('sys/mounts/platform',{'type':'kv','options':{'version':'2'}},token)
    auth=api('sys/auth',token=token)
    if 'kubernetes/' not in auth:api('sys/auth/kubernetes',{'type':'kubernetes'},token)
    # Vault uses its own rotating projected service-account token for TokenReview.
    api('auth/kubernetes/config',{'kubernetes_host':'https://kubernetes.default.svc:443'},token)
    audit=api('sys/audit',token=token)
    if 'file/' not in audit:api('sys/audit/file',{'type':'file','options':{'file_path':'/vault/audit/audit.log'}},token)
    profile=values()
    seed = None
    if initialize_new:
        pvcs = json.loads(kubectl('-n', profile['dataNamespace'], 'get', 'pvc', '-o', 'json', capture_output=True, text=True).stdout)['items']
        if pvcs:
            raise RuntimeError('Refusing fresh credentials while data PVCs exist; migrate existing Secrets instead')
        seed_path = 'platform/data/bootstrap/seed'
        if vault_path_exists(api, 'platform/metadata/bootstrap/seed', token):
            seed = api(seed_path, token=token)['data']['data']
        else:
            seed = {name: secrets.token_urlsafe(32) for name in ('postgres','redis','nats','auth','user','lobby','game','matchmaking','realtime','grafana')}
            seed['tokens'] = {name: secrets.token_urlsafe(32) for name in ('auth','game','lobby','matchmaking')}
            seed['loginKey'] = base64.b64encode(secrets.token_bytes(32)).decode()
            api(seed_path, {'options': {'cas': 0}, 'data': seed}, token)
    for item in profile['secret']:
        ns,name=item['namespace'],item['name']; identifier=ns+'-'+name
        policy='path "platform/data/'+ns+'/'+name+'" { capabilities = ["read"] }'
        api('sys/policies/acl/'+identifier,{'policy':policy},token)
        api('auth/kubernetes/role/'+identifier,{'bound_service_account_names':['sync-'+name], 'bound_service_account_namespaces':[ns], 'audience':'vault','token_policies':[identifier], 'token_ttl':'5m','token_max_ttl':'15m'},token)
        # Never overwrite Vault on repeat: it becomes the source of truth after import.
        metadata=api('platform/metadata/'+ns+'/'+name,token=token) if vault_path_exists(api,'platform/metadata/'+ns+'/'+name,token) else None
        if metadata is None:
            try:
                data=get_secret(ns,name)
            except subprocess.CalledProcessError:
                if seed is None:raise RuntimeError('Missing Kubernetes Secret '+ns+'/'+name+'; import into Vault, or use --initialize-new only on an empty environment')
                if name=='postgres-credential':
                    data={'POSTGRES_PASSWORD':seed['postgres'], **{key.upper()+'_DB_PASSWORD':seed[key] for key in ('auth','user','lobby','game','matchmaking','realtime','grafana')}}
                elif ns==profile['dataNamespace']:
                    key=name.removesuffix('-credential');data={key.upper()+'_PASSWORD':seed[key]}
                elif name=='grafana-admin':data={'password':seed['grafana']}
                elif name=='login-key':data={'login-keys.properties':'cluster-v1='+seed['loginKey']+'\n'}
                elif name.endswith('-service-credential'):data={'token':seed['tokens'][name.removesuffix('-service-credential')]}
                elif name=='ingress-tls':
                    data=issue_ingress_certificate()
                else:data={'password':seed[name.removesuffix('-credential')]}

            api('platform/data/'+ns+'/'+name,{'options':{'cas':0},'data':data},token)
    # CA private key is imported once for future issuance; never supplied to apps/VSO.
    if not vault_path_exists(api,'platform/metadata/authority/local-ca',token):
        certs=ROOT/'infra/local/certs'
        api('platform/data/authority/local-ca',{'options':{'cas':0},'data':{'tls.crt':(certs/'ca.crt').read_text(),'tls.key':(certs/'ca.key').read_text()}},token)


def vault_path_exists(api,path,token):
    try:api(path,token=token);return True
    except RuntimeError as error:
        if str(error).endswith('HTTP 404'):return False
        raise


def issue_ingress_certificate():
    ca = ROOT / 'infra/local/certs'
    with tempfile.TemporaryDirectory() as folder:
        path=Path(folder);os.chmod(path,0o700)
        (path/'extensions').write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\nsubjectAltName=DNS:tictactoe.test,IP:127.0.0.1\n')
        for command in [
            ['openssl','req','-new','-newkey','rsa:2048','-nodes','-keyout',str(path/'key'),'-out',str(path/'csr'),'-subj','/CN=tictactoe.test'],
            ['openssl','x509','-req','-in',str(path/'csr'),'-CA',str(ca/'ca.crt'),'-CAkey',str(ca/'ca.key'),'-set_serial','0x'+os.urandom(16).hex(),'-days','180','-extfile',str(path/'extensions'),'-out',str(path/'cert')],
        ]:subprocess.run(command,check=True,capture_output=True)
        return {'tls.crt':(path/'cert').read_text(),'tls.key':(path/'key').read_text()}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--unseal-only',action='store_true')
    parser.add_argument('--initialize-new',action='store_true',help='Generate initial credentials directly into Vault only when data namespace has no PVCs')
    args=parser.parse_args()
    if os.uname().sysname!='Darwin':raise SystemExit('Local bootstrap requires macOS Keychain; use the documented recovery procedure on other hosts')
    if not args.unseal_only:
        apply([item for chart in ('platform','observation') for item in documents(chart) if item['kind']=='Namespace'])
        apply([{'apiVersion':'v1','kind':'Namespace','metadata':{'name':NAMESPACE,'labels':{'istio-injection':'disabled'}}}])
        if args.initialize_new:
            helm('upgrade','--install','platform',str(KUBE_ROOT/'chart/platform'),'-n',values()['namespace'],'-f',str(KUBE_ROOT/'values.yaml'),'--take-ownership')
        if args.initialize_new:
            helm('upgrade','--install','observation',str(KUBE_ROOT/'chart/observation'),'-n',values()['observation']['namespace'],'-f',str(KUBE_ROOT/'values.yaml'),'--take-ownership')
        bootstrap_tls()
        helm('upgrade','--install','vault','hashicorp/vault','--version','0.34.1','-n',NAMESPACE,'-f',str(KUBE_ROOT/'vault/server-values.yaml'),'--post-renderer',str(KUBE_ROOT/'vault/post-render.sh'))
        kubectl('-n',NAMESPACE,'wait','--for=jsonpath={.status.phase}=Running','pod/vault-0','--timeout=180s')
    with connection() as api:
        initialized=api('sys/init')['initialized']; recovery=keychain_get()
        if not initialized:
            if recovery:raise RuntimeError('Keychain recovery exists for another Vault; refusing to replace it')
            recovery=api('sys/init',{'secret_shares':3,'secret_threshold':2})
            keychain_put(recovery)
        if not recovery:raise RuntimeError('Vault is initialized but recovery is missing from Keychain; restore recovery before continuing')
        if api('sys/seal-status')['sealed']:
            for key in recovery['keys_base64'][:2]:api('sys/unseal',{'key':key})
        if args.unseal_only:return
        ca=(ROOT/'infra/local/certs/ca.crt').read_text()
        for ns in sorted({item['namespace'] for item in values()['secret']}):
            put_bootstrap_secret(ns,'vault-ca',{'ca.crt':ca})
        configure(api,recovery,args.initialize_new)
    helm('upgrade','--install','vault-secrets-operator','hashicorp/vault-secrets-operator','--version','1.6.0','-n',NAMESPACE,'-f',str(KUBE_ROOT/'vault/operator-values.yaml'),'--wait','--timeout','180s')
    helm('upgrade','--install','secret',str(KUBE_ROOT/'chart/secret'),'-n',NAMESPACE,'-f',str(KUBE_ROOT/'values.yaml'),'--wait','--timeout','180s')
    for item in values()['secret']:
        kubectl('-n',item['namespace'],'wait','--for=condition=SecretSynced','vaultstaticsecret/'+item['name'],'--timeout=120s')
    print('Vault initialized/unsealed; recovery verified in Keychain; existing secrets imported and synchronized')


if __name__=='__main__':main()
