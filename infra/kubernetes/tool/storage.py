"""Single-node in-cluster stores for the 14.1 verification profile."""
import argparse
import json
from render import KUBE_ROOT, ROOT, resource


def render(v):
    ns, app = v['dataNamespace'], v['namespace']
    docs = [resource('ConfigMap', 'postgres-init', ns, data={'init-services.sh': (ROOT / 'infra/local/postgres/init-services.sh').read_text()}),
            resource('ConfigMap', 'jetstream', ns, data={'jetstream.json': (ROOT / 'infra/local/jetstream.json').read_text()})]
    for name, port, image, size in [('postgres', 5432, 'postgres:17.6-bookworm', '2Gi'),
                                    ('redis', 6379, 'redis:8.0.3-alpine', '1Gi'),
                                    ('nats', 4222, 'nats:2.11.4-alpine', '2Gi')]:
        container = {'name': name, 'image': image, 'ports': [{'name': 'tcp', 'containerPort': port}],
                     'envFrom': [{'secretRef': {'name': name + '-credential'}}],
                     'volumeMounts': [{'name': 'data', 'mountPath': '/var/lib/postgresql/data' if name == 'postgres' else '/data'}]}
        volumes = []
        if name == 'postgres':
            container['env'] = [{'name': 'PGDATA', 'value': '/var/lib/postgresql/data/pgdata'}]
            container['volumeMounts'].append({'name': 'init', 'mountPath': '/docker-entrypoint-initdb.d', 'readOnly': True})
            volumes.append({'name': 'init', 'configMap': {'name': 'postgres-init'}})
        else:
            container['command'] = ['/bin/sh', '-ec']
            container['args'] = ['exec redis-server --requirepass "$REDIS_PASSWORD" --appendonly yes' if name == 'redis' else
                                 'exec nats-server -js -sd /data -m 8222 --user app --pass "$NATS_PASSWORD"']
        docs.append(resource('Service', name, ns, {'selector': {'app': name}, 'ports': [
            {'name': 'tcp-' + name, 'port': port, 'targetPort': port}]}))
        docs.append(resource('StatefulSet', name, ns, {'serviceName': name, 'replicas': 1,
            'selector': {'matchLabels': {'app': name}}, 'template': {'metadata': {'labels': {'app': name},
                'annotations': {'sidecar.istio.io/inject': 'false'}},
                'spec': {'automountServiceAccountToken': False, 'containers': [container], 'volumes': volumes}},
            'volumeClaimTemplates': [{'metadata': {'name': 'data'}, 'spec': {'accessModes': ['ReadWriteOnce'],
                'storageClassName': v['storageClassName'], 'resources': {'requests': {'storage': size}}}}]}, 'apps/v1'))
    docs.append(resource('Job', 'bootstrap-stream', ns, {'backoffLimit': 3, 'template': {
        'metadata': {'labels': {'app': 'bootstrap-stream'}, 'annotations': {'sidecar.istio.io/inject': 'false'}},
        'spec': {'restartPolicy': 'Never', 'automountServiceAccountToken': False, 'containers': [
            {'name': 'bootstrap', 'image': f"{v['imagePrefix']}/matchmaking:{v['imageTag']}",
             'command': ['/bootstrap-streams', '-config', '/config/jetstream.json'],
             'env': [{'name': 'NATS_URL', 'value': 'nats://nats:4222'}, {'name': 'NATS_USER', 'value': 'app'}],
             'envFrom': [{'secretRef': {'name': 'nats-credential'}}],
             'volumeMounts': [{'name': 'config', 'mountPath': '/config', 'readOnly': True}]}],
            'volumes': [{'name': 'config', 'configMap': {'name': 'jetstream'}}]}}}, 'batch/v1'))
    for name, direction, selector, rule in [
        ('bootstrap-send', 'Egress', {'app': 'bootstrap-stream'}, {'to': [{'podSelector': {'matchLabels': {'app': 'nats'}}}], 'ports': [{'port': 4222, 'protocol': 'TCP'}]}),
        ('bootstrap-receive', 'Ingress', {'app': 'nats'}, {'from': [{'podSelector': {'matchLabels': {'app': 'bootstrap-stream'}}}], 'ports': [{'port': 4222, 'protocol': 'TCP'}]})]:
        docs.append(resource('NetworkPolicy', name, ns, {'podSelector': {'matchLabels': selector},
            'policyTypes': [direction], direction.lower(): [rule]}, 'networking.k8s.io/v1'))
    return docs


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    values = json.loads((KUBE_ROOT / 'values.json').read_text())
    text = json.dumps({'apiVersion': 'v1', 'kind': 'List', 'items': render(values)}, indent=2) + '\n'
    path = KUBE_ROOT / 'storage/store.json'
    if args.check:
        if path.read_text() != text:
            raise SystemExit('Storage manifests differ; regenerate them.')
    else:
        path.write_text(text)
