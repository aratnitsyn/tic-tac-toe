#!/usr/bin/env python3
"""Check real backend metrics, a JVM->Go->JVM trace, and provisioned Grafana dashboard."""
from contextlib import ExitStack, contextmanager
import base64
import http.client
import json
import ssl
import subprocess
import time
import urllib.parse
import uuid
from common import KUBECTL, ROOT, values
from smoke import request, require
from vault import connection, keychain_get


@contextmanager
def tunnel(namespace, service, local, remote):
    process=subprocess.Popen(KUBECTL+['-n',namespace,'port-forward','--address=127.0.0.1','service/'+service,str(local)+':'+str(remote)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        yield
    finally:
        process.terminate()
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:process.kill();process.wait()


def get(port,path,authorization=None):
    client=http.client.HTTPConnection('127.0.0.1',port,timeout=8)
    try:
        headers={'Authorization':authorization} if authorization else {}
        client.request('GET',path,headers=headers)
        response=client.getresponse();body=response.read()
        require(response.status==200,'Observation HTTP '+str(response.status)+' for '+path)
        return json.loads(body)
    finally:client.close()


def wait(check,timeout=60):
    deadline=time.monotonic()+timeout
    last=None
    while time.monotonic()<deadline:
        try:
            result=check()
            if result:return result
        except (OSError,RuntimeError,http.client.HTTPException) as error:last=error
        time.sleep(.5)
    raise RuntimeError('Observation check timeout'+(': '+str(last) if last else ''))


def main():
    profile=values();ns=profile['observation']['namespace']
    context=ssl.create_default_context(cafile=str(ROOT/'infra/local/certs/ca.crt'))
    token=None
    with ExitStack() as stack:
        for namespace,service,local,remote in [(profile['ingressNamespace'],'ingress',8443,443),(ns,'prometheus',18990,9090),(ns,'jaeger',18686,16686),(ns,'grafana',18300,3000)]:
            stack.enter_context(tunnel(namespace,service,local,remote))
        wait(lambda:request(context,'/api/v1/auth/csrf')[0]==200)
        try:
            account={'email':'trace-'+uuid.uuid4().hex+'@example.com','password':'Ka1!'+uuid.uuid4().hex[:11],'clientType':'NATIVE'}
            require(request(context,'/api/v1/auth/register','POST',account)[0]==201,'Registration failed')
            status,body=request(context,'/api/v1/auth/login','POST',account)
            require(status==200,'Login failed');token=body['data']['token'];user=body['data']['userId']
            status,body=request(context,'/api/v1/participation',token=token)
            require(status==200,'Participation failed')
            trace=uuid.uuid4().hex
            status,body=request(context,'/api/v1/matchmaking/queue','POST',{'expectedVersion':body['data']['version']},token,trace_id=trace)
            require(status in (200,201,202),'Queue command failed: HTTP '+str(status))
            def trace_found():
                result=get(18686,'/api/traces/'+trace)
                processes=[p['serviceName'] for item in result.get('data',[]) for p in item.get('processes',{}).values()]
                return result if {'api-gateway','auth','matchmaking','game'}.issubset(processes) else None
            wait(trace_found)
            print('PASS: one trace spans Gateway/Auth/Go Matchmaking/Game',flush=True)
            def targets_healthy():
                targets=get(18990,'/api/v1/targets')['data']['activeTargets']
                return len(targets)==8 and all(t['health']=='up' for t in targets)
            wait(targets_healthy)
            for metric in ('ttt_session_active','ttt_queue_available','ttt_timer_due','ttt_ws_connections','ttt_outbox_pending','ttt_observer_up'):
                wait(lambda metric=metric:bool(get(18990,'/api/v1/query?'+urllib.parse.urlencode({'query':metric}))['data']['result']))
            wait(lambda:len(get(18990,'/api/v1/query?'+urllib.parse.urlencode({'query':'ttt_observer_up == 1'}))['data']['result'])==7)
            series=get(18990,'/api/v1/series?'+urllib.parse.urlencode({'match[]':'{job=~".+"}'}))['data']
            prohibited={'userId','user_id','accountId','account_id','sessionId','session_id','token','email'}
            require(all(not prohibited.intersection(item) for item in series),'Forbidden metric labels')
            require(all(user not in str(item) and token not in str(item) for item in series),'User/token appeared in metric labels')
            print('PASS: all eight targets up; domain metrics present; no user/session/token labels',flush=True)
            with connection() as api:
                password=api('platform/data/'+ns+'/grafana-admin',token=keychain_get()['root_token'])['data']['data']['password']
            authorization='Basic '+base64.b64encode(('admin:'+password).encode()).decode()
            dashboard=get(18300,'/api/dashboards/uid/tictactoe-platform',authorization)
            require(len(dashboard['dashboard']['panels'])>=6,'Missing provisioned domain dashboard')
            print('PASS: provisioned Grafana dashboard is accessible with Vault-sourced admin credential',flush=True)
        finally:
            if token:
                status,_=request(context,'/api/v1/auth/logout','POST',token=token)
                require(status in (200,202),'Trace test logout failed')


if __name__=='__main__':main()
