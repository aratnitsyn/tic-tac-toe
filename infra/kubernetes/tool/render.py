#!/usr/bin/env python3
"""Render Kubernetes JSON (valid YAML) without third-party Python dependencies."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
KUBE_ROOT = ROOT / 'infra/kubernetes'
SERVICE_PORT = {'api-gateway': 8080, 'auth': 8081, 'user': 8082, 'lobby': 8083,
                'game': 8084, 'matchmaking': 8085, 'realtime': 8086, 'realtime-dispatcher': 8086}
JVM = {'api-gateway', 'auth', 'user', 'lobby', 'game'}


def resource(kind, name, namespace=None, spec=None, api='v1', **extra):
    result = {'apiVersion': api, 'kind': kind, 'metadata': {'name': name}}
    if namespace:
        result['metadata']['namespace'] = namespace
    if spec is not None:
        result['spec'] = spec
    return {**result, **extra}


def render(v):
    app, ingress, data = (v[k] for k in ('namespace', 'ingressNamespace', 'dataNamespace'))
    documents = []
    for ns in (app, ingress, data):
        item = resource('Namespace', ns)
        item['metadata']['labels'] = {'istio-injection': 'enabled' if ns != data else 'disabled'}
        documents.append(item)
    def add(kind, name, ns=app, spec=None, api='v1', **extra):
        documents.append(resource(kind, name, ns, spec, api, **extra))
    def service(name, port, ns=app, protocol='http'):
        add('Service', name, ns, {'selector': {'app': name}, 'ports': [
            {'name': protocol, 'port': port, 'targetPort': port}]})
    # Explicit plaintext at both receiving and sending Envoy proxies. Control-plane
    # xDS/CA TLS remains enabled; it is not application data-plane traffic.
    for ns in (app, ingress):
        add('PeerAuthentication', 'plaintext', ns, {'mtls': {'mode': 'DISABLE'}}, 'security.istio.io/v1')
    for ns, names in [(app, SERVICE_PORT), (data, ['postgres', 'redis', 'nats'])]:
        for name in names:
            add('DestinationRule', 'plaintext-' + name, ns,
                {'host': name + '.' + ns + '.svc.cluster.local', 'exportTo': ['*'],
                 'trafficPolicy': {'tls': {'mode': 'DISABLE'}}}, 'networking.istio.io/v1')
    common = {'NATS_URL': f'nats://nats.{data}.svc.cluster.local:4222', 'NATS_USER': 'app'}
    tokens = {'api-gateway': ['auth', 'game', 'lobby', 'matchmaking'], 'auth': ['auth', 'game'],
              'game': ['game'], 'lobby': ['lobby', 'game'], 'matchmaking': ['matchmaking', 'game'],
              'realtime': ['auth', 'game', 'matchmaking'], 'user': []}
    for name, port in SERVICE_PORT.items():
        source = 'realtime' if name == 'realtime-dispatcher' else name
        config = {'PORT': str(port)}
        if source != 'api-gateway':
            config.update(common)
            if source in JVM:
                config.update(PG_JDBC_URL=f'jdbc:postgresql://postgres.{data}.svc.cluster.local:5432/{source}', PGUSER=source)
            else:
                config['PG_DSN'] = f'host=postgres.{data}.svc.cluster.local port=5432 user={source} dbname={source} sslmode=disable'
        if source in ('auth', 'realtime'):
            config.update(REDIS_HOST=f'redis.{data}.svc.cluster.local', REDIS_PORT='6379',
                          REDIS_ADDRESS=f'redis.{data}.svc.cluster.local:6379')
        if source in ('api-gateway', 'auth', 'lobby', 'matchmaking', 'realtime'):
            config['GAME_URL'] = f'http://game.{app}.svc.cluster.local:8084'
        if source in ('api-gateway', 'realtime'):
            config['AUTH_URL'] = f'http://auth.{app}.svc.cluster.local:8081'
            config['MATCHMAKING_URL'] = f'http://matchmaking.{app}.svc.cluster.local:8085'
        if source == 'api-gateway':
            config.update(WEB_ORIGIN=v['webOrigin'], LOBBY_URL=f'http://lobby.{app}.svc.cluster.local:8083')
        if source == 'auth':
            config.update(AUTH_LOGIN_KEY_ID='cluster-v1', AUTH_LOGIN_KEY_FILE='/run/keys/login-keys.properties')
        if source == 'lobby':
            config.update(LOBBY_RETRY_MS='250', LOBBY_CODE_USER_LIMIT='10', LOBBY_CODE_IP_LIMIT='30')
        if source == 'game':
            config['COORDINATION_RETRY_DELAY_MS'] = '250'
        if source == 'matchmaking':
            config['MATCHMAKING_RETRY_MS'] = '250'
        if source == 'realtime':
            config.update(WEB_ORIGINS=v['webOrigin'], REALTIME_ROLE='dispatcher' if name.endswith('-dispatcher') else 'socket')
        if source in JVM:
            config['JAVA_TOOL_OPTIONS'] = '-Xms64m -Xmx256m -XX:ActiveProcessorCount=2'
        add('ConfigMap', name, data=config)
        env = []
        def secret(variable, secret_name, key):
            env.append({'name': variable, 'valueFrom': {'secretKeyRef': {'name': secret_name, 'key': key}}})
        if source != 'api-gateway':
            secret('PGPASSWORD', source + '-credential', 'password')
            secret('NATS_PASSWORD', 'nats-credential', 'password')
        if source in ('auth', 'realtime'):
            secret('REDIS_PASSWORD', 'redis-credential', 'password')
        for token in tokens[source]:
            secret(token.upper() + '_SERVICE_TOKEN', token + '-service-credential', 'token')
        if source == 'realtime':
            env.append({'name': 'REALTIME_POD_ID', 'valueFrom': {'fieldRef': {'fieldPath': 'metadata.name'}}})
        container = {'name': source, 'image': f"{v['imagePrefix']}/{source}:{v['imageTag']}",
                     'imagePullPolicy': 'IfNotPresent', 'ports': [{'name': 'http', 'containerPort': port}],
                     'envFrom': [{'configMapRef': {'name': name}}], 'env': env,
                     'securityContext': {'allowPrivilegeEscalation': False, 'runAsNonRoot': True,
                                         'runAsUser': 10001, 'capabilities': {'drop': ['ALL']}}}
        pod = {'automountServiceAccountToken': True, 'containers': [container]}
        if source == 'auth':
            container['volumeMounts'] = [{'name': 'login-key', 'mountPath': '/run/keys', 'readOnly': True}]
            pod['volumes'] = [{'name': 'login-key', 'secret': {'secretName': 'login-key', 'defaultMode': 292}}]
        add('Deployment', name, spec={'replicas': 1, 'selector': {'matchLabels': {'app': name}},
            'template': {'metadata': {'labels': {'app': name, 'sidecar.istio.io/inject': 'true'}}, 'spec': pod}}, api='apps/v1')
        service(name, port)
    # Dedicated gateway process: injection supplies the correct gateway Envoy image,
    # bootstrap and SDS credential access. This is not a backend sidecar template.
    add('ServiceAccount', 'ingress', ingress)
    add('Role', 'ingress-secret-reader', ingress, api='rbac.authorization.k8s.io/v1', rules=[
        {'apiGroups': [''], 'resources': ['secrets'], 'verbs': ['get', 'list', 'watch']}])
    add('RoleBinding', 'ingress-secret-reader', ingress, api='rbac.authorization.k8s.io/v1',
        roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'Role', 'name': 'ingress-secret-reader'},
        subjects=[{'kind': 'ServiceAccount', 'name': 'ingress', 'namespace': ingress}])
    add('Deployment', 'ingress', ingress, {'replicas': 1, 'selector': {'matchLabels': {'app': 'ingress'}},
        'template': {'metadata': {'labels': {'app': 'ingress', 'istio': 'tictactoe-ingress', 'sidecar.istio.io/inject': 'true'},
            'annotations': {'inject.istio.io/templates': 'gateway'}},
            'spec': {'serviceAccountName': 'ingress', 'containers': [{'name': 'istio-proxy', 'image': 'auto',
                     'ports': [{'containerPort': 8443, 'name': 'https'}]}]}}}, 'apps/v1')
    add('Service', 'ingress', ingress, {'type': v['ingressServiceType'], 'selector': {'app': 'ingress'},
         'ports': [{'name': 'https', 'port': 443, 'targetPort': 8443}]})
    add('Gateway', 'external', ingress, {'selector': {'istio': 'tictactoe-ingress'}, 'servers': [
        {'port': {'number': 443, 'name': 'https', 'protocol': 'HTTPS'}, 'hosts': v['hosts'],
         'tls': {'mode': 'SIMPLE', 'credentialName': 'ingress-tls', 'minProtocolVersion': 'TLSV1_2'}}]}, 'networking.istio.io/v1')
    add('VirtualService', 'external', ingress, {'hosts': v['hosts'], 'gateways': ['external'], 'http': [
        {'name': 'api', 'match': [{'uri': {'prefix': '/api/v1/'}}],
         'route': [{'destination': {'host': f'api-gateway.{app}.svc.cluster.local', 'port': {'number': 8080}}}]},
        {'name': 'socket', 'match': [{'uri': {'exact': '/wss'}}],
         'route': [{'destination': {'host': f'realtime.{app}.svc.cluster.local', 'port': {'number': 8086}}}]}]}, 'networking.istio.io/v1')
    # NetworkPolicy is the boundary, not a plaintext Istio source principal.
    def policy(name, ns, selector, ingress_rules=None, egress_rules=None):
        spec = {'podSelector': {'matchLabels': selector}, 'policyTypes': []}
        for kind, rules in [('Ingress', ingress_rules), ('Egress', egress_rules)]:
            if rules is not None:
                spec['policyTypes'].append(kind)
                spec[kind.lower()] = rules
        add('NetworkPolicy', name, ns, spec, 'networking.k8s.io/v1')
    def peer(ns, label=None):
        result = {'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': ns}}}
        if label:
            result['podSelector'] = {'matchLabels': {'app': label}}
        return result
    def ports(*values):
        return [{'protocol': 'TCP', 'port': value} for value in values]
    dns = {'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}},
                   'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}],
           'ports': [{'protocol': proto, 'port': 53} for proto in ('UDP', 'TCP')]}
    control = {'to': [peer('istio-system', 'istiod')], 'ports': ports(15012)}
    for ns in (app, ingress, data):
        policy('default-deny', ns, {}, [], [])
        policy('dns', ns, {}, egress_rules=[dns])
    policy('control-plane', app, {}, egress_rules=[control])
    policy('control-plane', ingress, {}, egress_rules=[control])
    callers = {'api-gateway': [(ingress, 'ingress')], 'auth': [(app, n) for n in ('api-gateway', 'realtime', 'realtime-dispatcher')],
        'user': [], 'lobby': [(app, 'api-gateway')], 'game': [(app, n) for n in ('api-gateway', 'auth', 'lobby', 'matchmaking', 'realtime', 'realtime-dispatcher')],
        'matchmaking': [(app, n) for n in ('api-gateway', 'realtime', 'realtime-dispatcher')], 'realtime': [(ingress, 'ingress')], 'realtime-dispatcher': []}
    for target, sources in callers.items():
        if sources:
            policy('receive-' + target, app, {'app': target}, ingress_rules=[
                {'from': [peer(ns, src) for ns, src in sources], 'ports': ports(SERVICE_PORT[target])}])
        for ns, src in sources:
            policy('send-' + src + '-to-' + target, ns, {'app': src}, egress_rules=[
                {'to': [peer(app, target)], 'ports': ports(SERVICE_PORT[target])}])
    for target, port, sources in [('postgres', 5432, [n for n in SERVICE_PORT if n != 'api-gateway']),
                                  ('redis', 6379, ['auth', 'realtime', 'realtime-dispatcher']),
                                  ('nats', 4222, [n for n in SERVICE_PORT if n != 'api-gateway'])]:
        policy('receive-' + target, data, {'app': target}, ingress_rules=[
            {'from': [peer(app, src) for src in sources], 'ports': ports(port)}])
        for src in sources:
            policy('store-' + src + '-to-' + target, app, {'app': src}, egress_rules=[
                {'to': [peer(data, target)], 'ports': ports(port)}])
    policy('external-https', ingress, {'app': 'ingress'}, ingress_rules=[{'ports': ports(8443)}])
    return documents


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    docs = render(json.loads((KUBE_ROOT / 'values.json').read_text()))
    text = json.dumps({'apiVersion': 'v1', 'kind': 'List', 'items': docs}, indent=2) + '\n'
    path = KUBE_ROOT / 'base/platform.json'
    if args.check:
        if path.read_text() != text:
            raise SystemExit('Manifests differ from values/render.py; regenerate them.')
    else:
        path.write_text(text)


if __name__ == '__main__':
    main()
