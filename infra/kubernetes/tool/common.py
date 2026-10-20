"""Shared paths, pinned profile and CLI execution; manifests live in YAML charts."""
import os
from pathlib import Path
import shutil
import subprocess
import yaml

ROOT = Path(__file__).resolve().parents[3]
KUBE_ROOT = ROOT / 'infra/kubernetes'
SERVICE_PORT = {'api-gateway': 8080, 'auth': 8081, 'user': 8082, 'lobby': 8083,
                'game': 8084, 'matchmaking': 8085, 'realtime': 8086, 'realtime-dispatcher': 8086}
JVM = {'api-gateway', 'auth', 'user', 'lobby', 'game'}
KUBECTL = ['kubectl', '--context', 'docker-desktop']


def helm_binary():
    local = KUBE_ROOT / '.tools/helm'
    return str(local) if local.exists() else (shutil.which('helm') or 'helm')


def values():
    return yaml.safe_load((KUBE_ROOT / 'values.yaml').read_text())


def kubectl(*args, **kwargs):
    return subprocess.run(KUBECTL + list(args), check=True, **kwargs)


def helm(*args, **kwargs):
    return subprocess.run([helm_binary(), '--kube-context', 'docker-desktop', *args], check=True, **kwargs)


def documents(chart):
    result = helm('template', chart, str(KUBE_ROOT / 'chart' / chart), '-f',
                  str(KUBE_ROOT / 'values.yaml'), capture_output=True, text=True)
    return [item for item in yaml.safe_load_all(result.stdout) if item]


def apply(items):
    # Runtime objects such as probe pods are YAML too; infrastructure sources
    # are Helm templates and never synthesized by this helper.
    kubectl('apply', '-f', '-', input=yaml.safe_dump_all(items), text=True)
