#!/usr/bin/env python3
"""Verify real HTTPS/WSS, Envoy plaintext and enforced NetworkPolicy (no TLS bypass)."""
import base64
import hashlib
import http.client
import json
import os
import socket
import ssl
import struct
import subprocess
import time
import uuid
from common import KUBE_ROOT, ROOT, SERVICE_PORT, values as profile
from common import KUBECTL, apply, kubectl


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def get_json(*args):
    result = kubectl(*args, '-o', 'json', capture_output=True, text=True)
    return json.loads(result.stdout)


def request(context, path, method='GET', body=None, token=None, command_id=None, trace_id=None):
    headers = {'X-Client-Type': 'NATIVE'}
    if trace_id:
        headers['traceparent'] = '00-' + trace_id + '-0123456789abcdef-01'
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if method != 'GET':
        headers.update({'Idempotency-Key': command_id or str(uuid.uuid4()), 'Content-Type': 'application/json'})
    client = http.client.HTTPSConnection('tictactoe.test', 8443, context=context, timeout=8)
    client._create_connection = lambda address, timeout, source_address=None: socket.create_connection(('127.0.0.1', 8443), timeout)
    try:
        client.request(method, path, json.dumps(body) if body is not None else None, headers)
        response = client.getresponse()
        payload = response.read()
        return response.status, json.loads(payload) if payload.startswith(b'{') else None
    finally:
        client.close()


def exact(stream, size):
    result = b''
    while len(result) < size:
        chunk = stream.recv(size - len(result))
        if not chunk:
            raise RuntimeError('Unexpected WebSocket EOF')
        result += chunk
    return result


def send(stream, body, opcode=1):
    data = json.dumps(body).encode() if opcode == 1 else body
    mask = os.urandom(4)
    length = len(data)
    header = bytes([128 | opcode, 128 | length]) if length < 126 else bytes([128 | opcode, 254]) + struct.pack('!H', length)
    stream.sendall(header + mask + bytes(value ^ mask[i % 4] for i, value in enumerate(data)))


def receive(stream):
    while True:
        first, second = exact(stream, 2)
        size = second & 127
        if size == 126:
            size = struct.unpack('!H', exact(stream, 2))[0]
        elif size == 127:
            size = struct.unpack('!Q', exact(stream, 8))[0]
        require(size <= 65536 and not second & 128, 'Invalid or oversized server frame')
        data = exact(stream, size)
        if first & 15 == 9:
            send(stream, data, 10)
            continue
        require(first & 15 == 1, 'Expected WebSocket text frame')
        return json.loads(data)


def websocket(context, ticket, origin):
    raw = socket.create_connection(('127.0.0.1', 8443), timeout=8)
    with context.wrap_socket(raw, server_hostname='tictactoe.test') as stream:
        key = base64.b64encode(os.urandom(16)).decode()
        stream.sendall(('GET /wss HTTP/1.1\r\nHost: tictactoe.test:8443\r\n'
            'Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n'
            'Sec-WebSocket-Key: ' + key + '\r\nOrigin: ' + origin + '\r\n\r\n').encode())
        response = b''
        while not response.endswith(b'\r\n\r\n'):
            response += exact(stream, 1)
            require(len(response) < 16384, 'Oversized upgrade headers')
        require(response.startswith(b'HTTP/1.1 101 '), 'WSS upgrade rejected')
        accept = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest())
        require(accept.lower() in response.lower(), 'Invalid WebSocket accept')
        send(stream, {'type': 'auth', 'ticket': ticket})
        require(receive(stream).get('type') == 'auth_ok', 'WSS auth did not succeed')
        request_id = str(uuid.uuid4())
        send(stream, {'type': 'heartbeat', 'requestId': request_id})
        reply = receive(stream)
        require(reply.get('type') == 'heartbeat_ack' and reply.get('requestId') == request_id, 'WSS heartbeat failed')
        send(stream, b'\x03\xe8', 8)


def check_plaintext(v):
    app, ingress, data = (v[key] for key in ('namespace', 'ingressNamespace', 'dataNamespace'))
    istioctl = str(KUBE_ROOT / '.tools/istioctl')
    checked = []
    for ns, names in [(app, list(SERVICE_PORT)), (ingress, ['ingress'])]:
        for name in names:
            pods = get_json('-n', ns, 'get', 'pod', '-l', 'app=' + name)['items']
            require(len(pods) == 1, 'Expected one local verification replica: ' + name)
            pod = pods[0]
            sidecars = pod['spec']['containers'] + [c for c in pod['spec'].get('initContainers', []) if c.get('restartPolicy') == 'Always']
            require('istio-proxy' in [c['name'] for c in sidecars], 'Missing Envoy: ' + name)
            result = subprocess.run([istioctl, '--context', 'docker-desktop', 'proxy-config', 'cluster',
                pod['metadata']['name'], '-n', ns, '-o', 'json'], check=True, capture_output=True, text=True)
            clusters = json.loads(result.stdout)
            relevant = [c for c in clusters if c.get('name', '').startswith('outbound|') and
                        any('.' + target + '.svc.cluster.local' in c.get('name', '') for target in (app, data))]
            require(relevant, 'No application clusters in Envoy: ' + name)
            for cluster in relevant:
                require(not cluster.get('transportSocket'), 'Unexpected upstream TLS: ' + cluster['name'])
                require(not cluster.get('transportSocketMatches'), 'Unexpected upstream transport selection: ' + cluster['name'])
            if ns == app:
                result = subprocess.run([istioctl, '--context', 'docker-desktop', 'proxy-config', 'listener',
                    pod['metadata']['name'], '-n', ns, '--port', '15006', '-o', 'json'],
                    check=True, capture_output=True, text=True)
                listeners = json.loads(result.stdout)
                port = SERVICE_PORT[name]
                chains = [chain for listener in listeners for chain in listener.get('filterChains', [])
                          if chain.get('filterChainMatch', {}).get('destinationPort') == port]
                require(chains, 'No inbound application listener: ' + name)
                for chain in chains:
                    require(not chain.get('transportSocket'), 'Unexpected inbound TLS: ' + name)
                    require(chain.get('filterChainMatch', {}).get('transportProtocol') != 'tls', 'TLS inbound match: ' + name)
            checked.append({'namespace': ns, 'workload': name, 'plaintextClusters': len(relevant)})
    return checked


def check_network(v):
    probe = 'tictactoe-probe-' + uuid.uuid4().hex[:8]
    # Dedicated namespace is intentionally outside the trusted selectors.
    # No service credentials or CA private keys are copied into the test pod.
    apply([{'apiVersion': 'v1', 'kind': 'Namespace', 'metadata': {'name': probe}},
        {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'probe', 'namespace': probe,
         'annotations': {'sidecar.istio.io/inject': 'false'}}, 'spec': {'automountServiceAccountToken': False,
         'containers': [{'name': 'probe', 'image': 'python:3.13.7-alpine', 'command': ['sleep', '3600']}]}}])
    try:
        kubectl('-n', probe, 'wait', '--for=condition=Ready', 'pod/probe', '--timeout=180s')
        targets = []
        for ns in (v['namespace'], v['dataNamespace'], v['observation']['namespace']):
            services = get_json('-n', ns, 'get', 'service')['items']
            pods = get_json('-n', ns, 'get', 'pod')['items']
            for service in services:
                name = service['metadata']['name']
                for port in service['spec']['ports']:
                    targets.append([name + '/service', service['spec']['clusterIP'], port['port'], False])
                    for pod in pods:
                        if pod['metadata'].get('labels', {}).get('app') == name and pod['status'].get('podIP'):
                            targets.append([name + '/pod', pod['status']['podIP'], port['targetPort'], False])
        gateway = get_json('-n', v['ingressNamespace'], 'get', 'service', 'ingress')
        require([p['port'] for p in gateway['spec']['ports']] == [443], 'Ingress exposes a non-TLS port')
        ingress_pods = get_json('-n', v['ingressNamespace'], 'get', 'pod')['items']
        ingress_pod = next(p for p in ingress_pods if p['metadata'].get('labels', {}).get('app') == 'ingress')
        targets += [['external-https/pod', ingress_pod['status']['podIP'], 8443, True],
                    ['external-https', gateway['spec']['clusterIP'], 443, True],
                    ['external-http', gateway['spec']['clusterIP'], 80, False]]
        code = '''import json,socket,ssl,sys
payload=json.load(sys.stdin)
for name,host,port,expected in payload['targets']:
 try:
  with socket.create_connection((host,port),timeout=5 if expected else 1): actual=True
 except OSError: actual=False
 if actual != expected: raise SystemExit('NetworkPolicy mismatch: '+name)
 print(name+': '+('allowed' if actual else 'blocked'))
context=ssl.create_default_context(cadata=payload['ca'])
address=(payload['ingress'],443)
with socket.create_connection(address,timeout=5) as raw:
 with context.wrap_socket(raw,server_hostname='tictactoe.test') as stream:
  stream.sendall(b'GET /api/v1/auth/csrf HTTP/1.1\\r\\nHost: tictactoe.test\\r\\nConnection: close\\r\\n\\r\\n')
  if not stream.recv(4096).startswith(b'HTTP/1.1 200'): raise SystemExit('Probe HTTPS positive control failed')
for name,trust in [('wrong.invalid',context),('tictactoe.test',ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT))]:
 try:
  with socket.create_connection(address,timeout=5) as raw:
   with trust.wrap_socket(raw,server_hostname=name): pass
 except (ssl.SSLError,ConnectionResetError): pass
 else: raise SystemExit('Invalid TLS peer was accepted')
with socket.create_connection(address,timeout=5) as raw:
 raw.sendall(b'GET /api/v1/auth/csrf HTTP/1.1\\r\\nHost: tictactoe.test\\r\\n\\r\\n')
 try: response=raw.recv(4096)
 except ConnectionResetError: response=b''
 if response.startswith((b'HTTP/1.1 200',b'HTTP/1.1 101')): raise SystemExit('Cleartext was accepted')
print('TLS CA/SNI verification and cleartext rejection: passed')
'''
        try:
            result = kubectl('-n', probe, 'exec', '-i', 'probe', '--', 'python', '-c', code,
                             input=json.dumps({'targets': targets, 'ingress': gateway['spec']['clusterIP'],
                                'ca': (ROOT / 'infra/local/certs/ca.crt').read_text()}), text=True, capture_output=True)
        except subprocess.CalledProcessError as error:
            print(error.stdout, end='')
            raise RuntimeError(error.stderr) from None
        print(result.stdout, end='')
        return len(targets)
    finally:
        kubectl('delete', 'namespace', probe, '--wait=false')


def main():
    v = profile()
    context = ssl.create_default_context(cafile=str(ROOT / 'infra/local/certs/ca.crt'))
    log = (KUBE_ROOT / '.local/port-forward.log').open('w')
    process = subprocess.Popen(KUBECTL + ['-n', v['ingressNamespace'], 'port-forward',
        '--address=127.0.0.1', 'service/ingress', '8443:443'], stdout=log, stderr=log)
    try:
        for _ in range(90):
            if process.poll() is not None:
                raise RuntimeError('Port-forward stopped; see .local/port-forward.log')
            try:
                status, _ = request(context, '/api/v1/auth/csrf')
                if status == 200:
                    break
            except (OSError, http.client.HTTPException):
                pass
            time.sleep(1)
        else:
            raise RuntimeError('HTTPS did not become ready')
        require(request(context, '/api/v1/auth/session')[0] == 401, 'Unauthenticated session accepted')
        for path in ['/realtime', '/wss/', '/api/v1', '/internal/v1/sessions/introspect', '/actuator/health']:
            require(request(context, path)[0] == 404, 'Unexpected external route: ' + path)
        account = {'email': 'kube-' + uuid.uuid4().hex + '@example.com', 'password': 'Ka1!' + uuid.uuid4().hex[:11], 'clientType': 'NATIVE'}
        require(request(context, '/api/v1/auth/register', 'POST', account)[0] == 201, 'Registration failed')
        status, body = request(context, '/api/v1/auth/login', 'POST', account)
        require(status == 200, 'Login failed')
        token = body['data']['token']
        require(request(context, '/api/v1/participation', token=token)[0] == 200, 'Gateway to Game failed')
        status, body = request(context, '/api/v1/auth/ws-ticket', 'POST', token=token)
        require(status == 200, 'Ticket failed')
        websocket(context, body['data']['ticket'], v['webOrigin'])
        print('HTTPS, WSS auth/heartbeat and route isolation: passed')
        plaintext = check_plaintext(v)
        count = check_network(v)
        require(request(context, '/api/v1/auth/logout', 'POST', token=token)[0] in (200, 202), 'Logout failed')
        report = {'task': '14.1', 'result': 'passed', 'istio': v['istioVersion'],
                  'plaintext': plaintext, 'networkChecks': count,
                  'limits': 'Single-node functional verification; no capacity, HA, egress or backup claim.'}
        (KUBE_ROOT / '.local/smoke-report.json').write_text(json.dumps(report, indent=2) + '\n')
        print('14.1 smoke passed; report: infra/kubernetes/.local/smoke-report.json')
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        log.close()


if __name__ == '__main__':
    main()
