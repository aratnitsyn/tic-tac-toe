#!/usr/bin/env python3
"""Install explicit egress; verify real TLS to a controlled HTTPS server outside Kubernetes."""
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading
import time
import yaml
from common import KUBE_ROOT, apply, documents, helm, kubectl, values
from vault import configure, connection, keychain_get, put_bootstrap_secret, vault_path_exists


class Endpoint(BaseHTTPRequestHandler):
    def do_GET(self):
        body=b'controlled-external-tls\n'
        self.send_response(200)
        self.send_header('Content-Length',str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self,*args):pass


def certificate(folder, ca, host, bad=False):
    folder.mkdir(mode=0o700)
    for name,content in ca.items():(folder/name).write_text(content)
    if bad:
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','2',
                        '-keyout',str(folder/'ca.key'),'-out',str(folder/'ca.crt'),'-subj','/CN=untrusted-egress-test'],check=True,capture_output=True)
    (folder/'extension').write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:'+host+'\n')
    for command in [
        ['openssl','req','-new','-newkey','rsa:2048','-nodes','-keyout',str(folder/'tls.key'),'-out',str(folder/'request'),'-subj','/CN='+host],
        ['openssl','x509','-req','-in',str(folder/'request'),'-CA',str(folder/'ca.crt'),'-CAkey',str(folder/'ca.key'),'-set_serial','0x'+os.urandom(16).hex(),'-days','2','-extfile',str(folder/'extension'),'-out',str(folder/'tls.crt')],
    ]:subprocess.run(command,check=True,capture_output=True)
    return {name:(folder/name).read_text() for name in ('tls.crt','tls.key','ca.crt','ca.key')}


def main():
    profile=values(); namespace=profile['egress']['namespace']; host=profile['egress']['host']
    recovery=keychain_get()
    apply([item for item in documents('platform') if item['kind']=='Namespace'])
    with tempfile.TemporaryDirectory() as directory, connection() as api:
        root=api('platform/data/authority/local-ca',token=recovery['root_token'])['data']['data']
        ca={'ca.crt':root['tls.crt'],'ca.key':root['tls.key']}
        # Test leaf/private keys and the deliberately untrusted test CA also originate in Vault.
        for name,bad in [('trusted',False),('untrusted',True)]:
            path='platform/data/authority/egress-test-'+name
            exists=vault_path_exists(api,path.replace('/data/','/metadata/'),recovery['root_token'])
            renew=not exists
            if exists:
                current=api(path,token=recovery['root_token'])['data']['data']
                renew=subprocess.run(['openssl','x509','-checkend','86400','-noout'],input=current['tls.crt'].encode(),capture_output=True).returncode!=0
            if renew:
                material=certificate(Path(directory)/name,ca,host,bad)
                api(path,{'data':material},recovery['root_token'])
        for name in ('vault-ca','egress-ca'):
            path='platform/data/'+namespace+'/'+name
            if not vault_path_exists(api,path.replace('/data/','/metadata/'),recovery['root_token']):
                api(path,{'data':{'ca.crt':ca['ca.crt']}},recovery['root_token'])
        put_bootstrap_secret(namespace,'vault-ca',{'ca.crt':ca['ca.crt']})
        configure(api,recovery)
    helm('upgrade','secret',str(KUBE_ROOT/'chart/secret'),'-n','tictactoe-vault','-f',str(KUBE_ROOT/'values.yaml'),'--wait','--timeout','120s')
    for name in ('vault-ca','egress-ca'):
        kubectl('-n',namespace,'wait','--for=condition=SecretSynced','vaultstaticsecret/'+name,'--timeout=90s')
    helm('upgrade','platform',str(KUBE_ROOT/'chart/platform'),'-n',profile['namespace'],'-f',str(KUBE_ROOT/'values.yaml'),'--take-ownership','--wait','--timeout','180s')
    helm('upgrade','mesh',str(KUBE_ROOT/'chart/mesh'),'-n',profile['namespace'],'-f',str(KUBE_ROOT/'values.yaml'))
    kubectl('-n',namespace,'rollout','status','deployment/egress','--timeout=120s')
    with ExitStack() as stack:
        folder=Path(stack.enter_context(tempfile.TemporaryDirectory()))
        os.chmod(folder,0o700)
        with connection() as api:
            for name,port in [('trusted',18443),('untrusted',18444)]:
                material=api('platform/data/authority/egress-test-'+name,token=recovery['root_token'])['data']['data']
                for part in ('tls.crt','tls.key'):
                    path=folder/(name+'-'+part);path.write_text(material[part]);path.chmod(0o600)
                context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                context.load_cert_chain(folder/(name+'-tls.crt'),folder/(name+'-tls.key'))
                # MacBook endpoint is outside Kubernetes; only temporary TLS listeners, no HTTP fallback.
                server=ThreadingHTTPServer(('0.0.0.0',port),Endpoint)
                server.socket=context.wrap_socket(server.socket,server_side=True)
                stack.callback(server.server_close);stack.callback(server.shutdown)
                threading.Thread(target=server.serve_forever,daemon=True).start()
        existing=kubectl('-n',profile['namespace'],'get','pod/egress-probe','--ignore-not-found','-o','name',capture_output=True,text=True)
        if existing.stdout.strip():raise RuntimeError('Egress probe already exists; refusing to replace it')
        kubectl('create','-f','-',input=yaml.safe_dump_all(documents('egress-probe')),text=True)
        stack.callback(lambda:kubectl('-n',profile['namespace'],'delete','pod/egress-probe','--ignore-not-found','--wait=true'))
        kubectl('-n',profile['namespace'],'wait','--for=condition=Ready','pod/egress-probe','--timeout=120s')
        def curl(*argument):
            return kubectl('-n',profile['namespace'],'exec','egress-probe','-c','probe','--','curl','-sS','--connect-timeout','3','--max-time','6',*argument,capture_output=True,text=True)
        def through():
            return curl('--resolve',host+':80:240.240.0.10','-o','/dev/null','-w','%{http_code}','http://'+host+'/probe').stdout
        for _ in range(30):
            if through()=='200':break
            time.sleep(1)
        else:raise RuntimeError('TLS origination did not reach controlled endpoint')
        print('PASS: mesh HTTP -> dedicated egress -> real external HTTPS',flush=True)
        # Direct, valid HTTPS is blocked by NetworkPolicy even when certificate/Host are correct.
        command=['kubectl','--context','docker-desktop','-n',profile['namespace'],'exec','egress-probe','-c','probe','--','curl','-sS','--connect-timeout','2','--max-time','3','--cacert','/tls/ca.crt','--resolve',host+':18443:'+profile['egress']['endpointIP'],'https://'+host+':18443/probe']
        result=subprocess.run(command,capture_output=True,text=True)
        if result.returncode not in (7,28):raise RuntimeError('Direct external connection was not blocked at TCP layer')
        print('PASS: direct external HTTPS bypass blocked by NetworkPolicy',flush=True)
        def verification_failures():
            stats=kubectl('-n',namespace,'exec','deployment/egress','-c','istio-proxy','--','curl','-sS','http://127.0.0.1:15000/stats?filter=ssl.fail_verify',capture_output=True,text=True).stdout
            return sum(int(line.rsplit(': ',1)[1]) for line in stats.splitlines() if ': ' in line)
        baseline=verification_failures()
        try:
            helm('upgrade','mesh',str(KUBE_ROOT/'chart/mesh'),'-n',profile['namespace'],'-f',str(KUBE_ROOT/'values.yaml'),'--set','egress.endpointPort=18444')
            for _ in range(30):
                if through()=='503':break
                time.sleep(1)
            else:raise RuntimeError('Untrusted external certificate was not rejected')
            if verification_failures() <= baseline:
                raise RuntimeError('No TLS verification failure counter; 503 alone is insufficient')
            print('PASS: untrusted external certificate rejected; Envoy TLS verification counter increased',flush=True)
        finally:
            helm('upgrade','mesh',str(KUBE_ROOT/'chart/mesh'),'-n',profile['namespace'],'-f',str(KUBE_ROOT/'values.yaml'))
        for _ in range(30):
            if through()=='200':break
            time.sleep(1)
        else:raise RuntimeError('Trusted TLS did not recover')
        print('PASS: trusted destination restored without cleartext fallback',flush=True)


if __name__=='__main__':main()
