"""Boundary checks for the generated local Kubernetes profile."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('kubernetes_render', ROOT / 'infra/kubernetes/tool/render.py')
renderer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(renderer)


class KubernetesManifestTest(unittest.TestCase):
    def setUp(self):
        self.values = json.loads((ROOT / 'infra/kubernetes/values.json').read_text())
        self.items = renderer.render(self.values)

    def test_only_https_and_exact_websocket_are_public(self):
        virtual = next(item for item in self.items if item['kind'] == 'VirtualService')
        self.assertEqual([rule['match'] for rule in virtual['spec']['http']],
                         [[{'uri': {'prefix': '/api/v1/'}}], [{'uri': {'exact': '/wss'}}]])
        gateway = next(item for item in self.items if item['kind'] == 'Gateway')
        self.assertEqual([server['port']['protocol'] for server in gateway['spec']['servers']], ['HTTPS'])
        services = [item for item in self.items if item['kind'] == 'Service' and item['spec'].get('type') in ('LoadBalancer', 'NodePort')]
        self.assertEqual(len(services), 1)
        self.assertEqual([port['port'] for port in services[0]['spec']['ports']], [443])

    def test_mesh_plaintext_is_explicit_and_exported_to_ingress(self):
        peers = [item for item in self.items if item['kind'] == 'PeerAuthentication']
        self.assertEqual({item['metadata']['namespace'] for item in peers},
                         {self.values['namespace'], self.values['ingressNamespace']})
        self.assertTrue(all(item['spec']['mtls']['mode'] == 'DISABLE' for item in peers))
        rules = [item for item in self.items if item['kind'] == 'DestinationRule']
        self.assertEqual(len(rules), 11)
        for rule in rules:
            self.assertEqual(rule['spec']['trafficPolicy']['tls']['mode'], 'DISABLE')
            self.assertEqual(rule['spec']['exportTo'], ['*'])
            self.assertTrue(rule['spec']['host'].endswith('.' + rule['metadata']['namespace'] + '.svc.cluster.local'))
            self.assertNotIn('*', rule['spec']['host'])

    def test_default_deny_and_all_backend_sidecars(self):
        denies = [item for item in self.items if item['kind'] == 'NetworkPolicy' and item['metadata']['name'] == 'default-deny']
        self.assertEqual(len(denies), 3)
        for item in denies:
            self.assertEqual(item['spec']['ingress'], [])
            self.assertEqual(item['spec']['egress'], [])
        deployments = [item for item in self.items if item['kind'] == 'Deployment']
        self.assertEqual(len(deployments), 9)
        for item in deployments:
            self.assertEqual(item['spec']['template']['metadata']['labels']['sidecar.istio.io/inject'], 'true')

    def test_rendered_manifest_matches_source_and_values(self):
        actual = json.loads((ROOT / 'infra/kubernetes/base/platform.json').read_text())
        self.assertEqual(actual['items'], self.items)
