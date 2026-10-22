"""Boundary checks for the generated local Kubernetes profile."""
import json
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]


class KubernetesManifestTest(unittest.TestCase):
    def setUp(self):
        interpreter = ROOT / 'infra/kubernetes/.tools/venv/bin/python'
        command = [str(interpreter) if interpreter.exists() else sys.executable,
                   str(ROOT / 'infra/kubernetes/tool/check.py'), '--json']
        self.items = json.loads(subprocess.check_output(command, text=True))
        self.values = {'namespace': 'tictactoe', 'ingressNamespace': 'tictactoe-ingress'}

    def test_only_https_and_exact_websocket_are_public(self):
        virtual = next(item for item in self.items if item['kind'] == 'VirtualService' and item['metadata']['name'] == 'external')
        self.assertEqual([rule['match'] for rule in virtual['spec']['http']],
                         [[{'uri': {'prefix': '/api/v1/'}}], [{'uri': {'exact': '/wss'}}]])
        gateway = next(item for item in self.items if item['kind'] == 'Gateway' and item['metadata']['name'] == 'external')
        self.assertEqual([server['port']['protocol'] for server in gateway['spec']['servers']], ['HTTPS'])
        services = [item for item in self.items if item['kind'] == 'Service' and item['spec'].get('type') in ('LoadBalancer', 'NodePort')]
        self.assertEqual(len(services), 1)
        self.assertEqual([port['port'] for port in services[0]['spec']['ports']], [443])

    def test_mesh_plaintext_is_explicit_and_exported_to_ingress(self):
        peers = [item for item in self.items if item['kind'] == 'PeerAuthentication']
        self.assertEqual({item['metadata']['namespace'] for item in peers},
                         {self.values['namespace'], self.values['ingressNamespace'], 'tictactoe-egress'})
        self.assertTrue(all(item['spec']['mtls']['mode'] == 'DISABLE' for item in peers))
        rules = [item for item in self.items if item['kind'] == 'DestinationRule']
        self.assertEqual(len(rules), 15)
        external = next(rule for rule in rules if rule['metadata']['name'] == 'external-tls')
        tls = external['spec']['trafficPolicy']['portLevelSettings'][0]['tls']
        self.assertEqual(tls['mode'], 'SIMPLE')
        self.assertEqual(tls['credentialName'], 'egress-ca')
        self.assertEqual(tls['subjectAltNames'], [external['spec']['host']])
        self.assertEqual(tls['sni'], external['spec']['host'])
        self.assertNotIn('insecureSkipVerify', tls)
        for rule in rules:
            if rule is external:
                continue
            self.assertEqual(rule['spec']['trafficPolicy']['tls']['mode'], 'DISABLE')
            self.assertEqual(rule['spec']['exportTo'], ['*'])
            self.assertTrue(rule['spec']['host'].endswith('.' + rule['metadata']['namespace'] + '.svc.cluster.local'))
            self.assertNotIn('*', rule['spec']['host'])

    def test_default_deny_and_all_backend_sidecars(self):
        denies = [item for item in self.items if item['kind'] == 'NetworkPolicy' and item['metadata']['name'] == 'default-deny']
        self.assertEqual(len(denies), 6)
        for item in denies:
            self.assertEqual(item['spec']['ingress'], [])
            self.assertEqual(item['spec']['egress'], [])
        deployments = [item for item in self.items if item['kind'] == 'Deployment' and item['metadata']['namespace'] in ('tictactoe', 'tictactoe-ingress', 'tictactoe-egress')]
        self.assertEqual(len(deployments), 10)
        for item in deployments:
            self.assertEqual(item['spec']['template']['metadata']['labels']['sidecar.istio.io/inject'], 'true')

    def test_mesh_services_have_explicit_routes(self):
        routes = [item for item in self.items if item['kind'] == 'VirtualService'
                  and item['metadata']['name'].startswith('mesh-')]
        self.assertEqual(len(routes), 8)
        for item in routes:
            self.assertEqual(item['spec']['gateways'], ['mesh'])
            self.assertEqual(item['spec']['hosts'][0],
                             item['spec']['http'][0]['route'][0]['destination']['host'])

    def test_config_is_in_configmap_and_secrets_are_references(self):
        configs = {item['metadata']['name']: item['data'] for item in self.items
                   if item['kind'] == 'ConfigMap'}
        for item in self.items:
            if item['kind'] != 'Deployment' or item['metadata']['namespace'] != 'tictactoe':
                continue
            name = item['metadata']['name']
            container = item['spec']['template']['spec']['containers'][0]
            self.assertEqual(container['envFrom'], [{'configMapRef': {'name': name}}])
            self.assertIn('PORT', configs[name])
            for env in container.get('env', []):
                self.assertNotIn('value', env)
                self.assertIn('valueFrom', env)

    def test_kubernetes_and_istio_are_separate_yaml_charts(self):
        for chart in ('platform', 'storage', 'mesh', 'secret', 'observation'):
            folder = ROOT / 'infra/kubernetes/chart' / chart / 'templates'
            for path in folder.iterdir():
                self.assertEqual(path.suffix, '.yaml')
                content = path.read_text()
                api = next(line for line in content.splitlines() if line.startswith(('"apiVersion":', 'apiVersion:')))
                self.assertEqual('.istio.io/' in api, chart == 'mesh')

    def test_vault_sync_has_per_secret_serviceaccount_and_no_static_token(self):
        syncs = [x for x in self.items if x['kind'] == 'VaultStaticSecret']
        self.assertGreater(len(syncs), 0)
        auths = {(x['metadata']['namespace'], x['metadata']['name']): x
                 for x in self.items if x['kind'] == 'VaultAuth'}
        for item in syncs:
            namespace = item['metadata']['namespace']
            name = item['metadata']['name']
            spec = item['spec']
            self.assertEqual(spec['path'], namespace + '/' + name)
            self.assertEqual(spec['type'], 'kv-v2')
            self.assertEqual(spec['mount'], 'platform')
            auth = auths[(namespace, spec['vaultAuthRef'])]['spec']
            self.assertEqual(auth['method'], 'kubernetes')
            self.assertEqual(auth['kubernetes']['role'], namespace + '-' + name)
            self.assertEqual(auth['kubernetes']['serviceAccount'], 'sync-' + name)
            self.assertEqual(auth['kubernetes']['audiences'], ['vault'])
        connections = [x for x in self.items if x['kind'] == 'VaultConnection']
        self.assertEqual(len(connections), 6)
        for item in connections:
            self.assertTrue(item['spec']['address'].startswith('https://'))
            self.assertFalse(item['spec']['skipTLSVerify'])

    def test_required_secret_references_have_a_vault_source(self):
        sources = {(x['metadata']['namespace'], x['spec']['destination']['name'])
                   for x in self.items if x['kind'] == 'VaultStaticSecret'}
        for item in self.items:
            if item['kind'] not in ('Deployment', 'StatefulSet', 'Job'):
                continue
            namespace = item['metadata']['namespace']
            pod = item['spec']['template']['spec']
            for container in pod['containers']:
                for env in container.get('env', []):
                    secret = env.get('valueFrom', {}).get('secretKeyRef')
                    if secret:
                        self.assertIn((namespace, secret['name']), sources)
                        self.assertFalse(secret.get('optional', False))
            for volume in pod.get('volumes', []):
                if 'secret' in volume:
                    self.assertIn((namespace, volume['secret']['secretName']), sources)

    def test_rollout_waits_for_app_health_with_bounded_resources(self):
        deployments = [x for x in self.items if x['kind'] == 'Deployment' and x['metadata']['namespace'] in ('tictactoe', 'tictactoe-ingress', 'tictactoe-egress')]
        for item in deployments:
            spec = item['spec']
            self.assertEqual(spec['strategy']['rollingUpdate'], {'maxSurge': 1, 'maxUnavailable': 0})
            pod = spec['template']['spec']
            self.assertGreaterEqual(pod['terminationGracePeriodSeconds'], 30)
            app = pod['containers'][0]
            self.assertIn('memory', app['resources']['limits'])
            self.assertIn('cpu', app['resources']['requests'])
            if item['metadata']['name'] not in ('ingress', 'egress'):
                for probe in ('startupProbe', 'readinessProbe', 'livenessProbe'):
                    self.assertIn('httpGet', app[probe])
                    self.assertGreater(app[probe]['timeoutSeconds'], 0)
        budgets = [x for x in self.items if x['kind'] == 'PodDisruptionBudget']
        self.assertEqual(len(budgets), 8)

    def test_bootstrap_job_can_run_again_when_image_changes(self):
        job = next(x for x in self.items if x['kind'] == 'Job' and x['metadata']['name'] == 'bootstrap-stream')
        annotations = job['metadata']['annotations']
        self.assertEqual(set(annotations['helm.sh/hook'].split(',')), {'post-install', 'post-upgrade'})
        self.assertEqual(set(annotations['helm.sh/hook-delete-policy'].split(',')),
                         {'before-hook-creation', 'hook-succeeded'})
        self.assertFalse(any('persistentVolumeClaim' in v
                             for v in job['spec']['template']['spec'].get('volumes', [])))

    def test_egress_does_not_add_public_listener_or_direct_mesh_access(self):
        entry = next(x for x in self.items if x['kind'] == 'ServiceEntry')
        self.assertEqual(entry['spec']['location'], 'MESH_EXTERNAL')
        gateway = next(x for x in self.items if x['kind'] == 'Gateway' and x['metadata']['name'] == 'external-egress')
        self.assertEqual(gateway['metadata']['namespace'], 'tictactoe-egress')
        route = next(x for x in self.items if x['kind'] == 'VirtualService' and x['metadata']['name'] == 'controlled-external')
        self.assertEqual(route['spec']['http'][0]['match'], [{'gateways': ['mesh'], 'port': 80}])
        self.assertEqual(route['spec']['http'][0]['route'][0]['destination']['host'],
                         'egress.tictactoe-egress.svc.cluster.local')
        for policy in self.items:
            if policy['kind'] == 'NetworkPolicy' and policy['metadata']['namespace'] == 'tictactoe':
                for rule in policy['spec'].get('egress', []):
                    self.assertTrue(all('ipBlock' not in target for target in rule.get('to', [])))
