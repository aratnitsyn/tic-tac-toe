#!/usr/bin/env python3
"""Real HTTPS PvP: keep WS heartbeats, acknowledge a move, roll Game, replay safely."""
import base64
import hashlib
import json
import os
import socket
import ssl
import subprocess
import threading
import time
import uuid
from common import KUBE_ROOT, ROOT, KUBECTL, kubectl, values
from smoke import request, exact, send, receive, require


class Connection:
    def __init__(self, context, token, origin):
        self.context, self.token, self.origin = context, token, origin
        self.stop = threading.Event()
        self.error, self.last_ack, self.reconnects = None, 0, 0
        self.stream = self.connect()
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def connect(self):
        status, body = request(self.context, '/api/v1/auth/ws-ticket', 'POST', token=self.token)
        require(status==200, 'Reconnect ticket unavailable: HTTP '+str(status))
        raw = socket.create_connection(('127.0.0.1', 8443), timeout=8)
        stream = self.context.wrap_socket(raw, server_hostname='tictactoe.test')
        try:
            key = base64.b64encode(os.urandom(16)).decode()
            stream.sendall(('GET /wss HTTP/1.1\r\nHost: tictactoe.test:8443\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: '+key+'\r\nOrigin: '+self.origin+'\r\n\r\n').encode())
            response = b''
            while not response.endswith(b'\r\n\r\n'):
                response += exact(stream, 1)
                require(len(response)<16384, 'Oversized WSS response')
            accept = base64.b64encode(hashlib.sha1((key+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest())
            require(response.startswith(b'HTTP/1.1 101 ') and accept.lower() in response.lower(), 'WSS handshake failed')
            send(stream, {'type':'auth','ticket':body['data']['ticket']})
            require(receive(stream).get('type')=='auth_ok', 'WSS authentication failed')
            return stream
        except BaseException:
            stream.close()
            raise

    def run(self):
        outage = None
        while not self.stop.is_set():
            try:
                if self.stream is None:
                    self.stream = self.connect()
                    self.reconnects += 1
                identifier = str(uuid.uuid4())
                send(self.stream, {'type':'heartbeat','requestId':identifier})
                while True:
                    message = receive(self.stream)
                    if message.get('type')=='heartbeat_ack' and message.get('requestId')==identifier:
                        self.last_ack = time.monotonic()
                        outage = None
                        break
                self.stop.wait(1)
            except (OSError,RuntimeError) as error:
                if self.stop.is_set():return
                if self.stream is not None:self.stream.close()
                self.stream = None
                outage = outage or time.monotonic()
                if time.monotonic()-outage>30:
                    self.error = error
                    return
                self.stop.wait(.5)

    def close(self):
        self.stop.set()
        if self.stream is not None:self.stream.close()
        self.thread.join(timeout=10)

def main():
    profile = values()
    context = ssl.create_default_context(cafile=str(ROOT/'infra/local/certs/ca.crt'))
    log = (KUBE_ROOT/'.local/rolling-port-forward.log').open('w')
    tunnel = subprocess.Popen(KUBECTL+['-n',profile['ingressNamespace'],'port-forward','--address=127.0.0.1','service/ingress','8443:443'],stdout=log,stderr=log)
    tokens, sockets = {}, []
    def call(path, token, method='GET', body=None, command=None):
        status,response = request(context,'/api/v1'+path,method,body,token,command)
        require(status in (200,201,202), 'HTTP '+str(status)+' for '+path)
        return response['data']
    try:
        for _ in range(60):
            try:
                if request(context,'/api/v1/auth/csrf')[0]==200:break
            except OSError:pass
            time.sleep(1)
        else:raise RuntimeError('Ingress did not become available')
        for _ in range(2):
            account={'email':'rolling-'+uuid.uuid4().hex+'@example.com','password':'Ka1!'+uuid.uuid4().hex[:11],'clientType':'NATIVE'}
            status,_=request(context,'/api/v1/auth/register','POST',account)
            require(status==201,'Registration failed')
            status,response=request(context,'/api/v1/auth/login','POST',account)
            require(status==200,'Login failed')
            session=response['data'];token=session['token'];tokens[session['userId']]=token
            sockets.append(Connection(context,token,profile['webOrigin']))
        first,second=list(tokens.values())
        create_id=str(uuid.uuid4())
        create_payload={'expectedVersion':call('/participation',first)['version']}
        lobby=call('/lobbies',first,'POST',create_payload,create_id)
        call('/lobbies/join',second,'POST',{'expectedVersion':call('/participation',second)['version'],'code':lobby['code']})
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            state=call('/participation',first)
            if state['state']=='IN_MATCH':break
            time.sleep(.1)
        else:raise RuntimeError('Lobby did not produce a match')
        match_id=state['resourceId']
        barrier=time.monotonic()
        deadline=barrier+4
        while not all(connection.last_ack>barrier for connection in sockets):
            require(time.monotonic()<deadline, 'Fresh participation heartbeat did not complete')
            time.sleep(.05)
        for token in (first,second):
            match=call('/matches/'+match_id,token)
            call('/matches/'+match_id+'/ready',token,'POST',{'expectedVersion':match['version']})
        deadline=time.monotonic()+12
        while time.monotonic()<deadline:
            status,response=request(context,'/api/v1/games/'+match_id,token=first)
            if status==200 and response['data']['state']=='X_TURN':
                game=response['data'];break
            time.sleep(.1)
        else:raise RuntimeError('Game did not start')
        command=str(uuid.uuid4());payload={'expectedVersion':game['version'],'cell':0}
        x_token=tokens[game['xPlayerId']]
        moved=call('/games/'+match_id+'/moves',x_token,'POST',payload,command)
        require(moved['board'][0]=='X','Move was not acknowledged')
        kubectl('-n',profile['namespace'],'rollout','restart','deployment/game')
        kubectl('-n',profile['namespace'],'rollout','status','deployment/game','--timeout=180s')
        deadline=time.monotonic()+30
        while True:
            status,response=request(context,'/api/v1/games/'+match_id,token=first)
            if status==200:
                current=response['data'];break
            require(status in (502,503,504) and time.monotonic()<deadline, 'Game did not recover after rollout: HTTP '+str(status))
            time.sleep(.25)
        require(current['board'][0]=='X' and current['version']>=moved['version'],'Acknowledged move lost after rolling restart')
        replay=call('/games/'+match_id+'/moves',x_token,'POST',payload,command)
        require(replay['board']==moved['board'] and replay['version']==moved['version'],'Move replay changed result')
        # Replaying the original create operation must not create a second lobby/match.
        replay_lobby=call('/lobbies',first,'POST',create_payload,create_id)
        require(replay_lobby['lobbyId']==lobby['lobbyId'],'Lobby command replay created another resource')
        query="SELECT count(*) FROM game_match WHERE source_ref='"+str(uuid.UUID(lobby['lobbyId']))+"'"
        count=kubectl('-n',profile['dataNamespace'],'exec','postgres-0','--','bash','-ec',
                      'export PGPASSWORD="$GAME_DB_PASSWORD"; exec psql -X -h 127.0.0.1 -U game -d game -At -v ON_ERROR_STOP=1 -c "$1"','verify',query,capture_output=True,text=True).stdout.strip()
        require(count=='1','Rolling/replay produced duplicate matches')
        deadline=time.monotonic()+10
        while not all(connection.last_ack>time.monotonic()-3 for connection in sockets):
            require(time.monotonic()<deadline and all(connection.error is None for connection in sockets), 'WSS did not recover within the same sessions')
            time.sleep(.1)
        print('PASS: acknowledged move survives Game rollout; command replay is stable; one match per lobby')
    finally:
        cleanup_errors=[]
        for token in tokens.values():
            try:
                status,_=request(context,'/api/v1/auth/logout','POST',token=token)
                if status not in (200,202,401):cleanup_errors.append('logout HTTP '+str(status))
            except (OSError,RuntimeError) as error:
                cleanup_errors.append(type(error).__name__)
        for connection in sockets:connection.close()
        tunnel.terminate()
        try:tunnel.wait(timeout=5)
        except subprocess.TimeoutExpired:tunnel.kill();tunnel.wait()
        log.close()
        if cleanup_errors:raise RuntimeError("Test-account cleanup failed: "+", ".join(cleanup_errors))


if __name__=='__main__':main()
