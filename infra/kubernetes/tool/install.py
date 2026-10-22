#!/usr/bin/env python3
"""Install pinned local tools, NetworkPolicy controller and Istio Helm releases."""
import hashlib
from pathlib import Path
import io
import json
import platform
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
KUBE_ROOT = ROOT / 'infra/kubernetes'
HELM_VERSION = '3.19.0'
HELM_SHA256 = '31513e1193da4eb4ae042eb5f98ef9aca7890cfa136f4707c8d4f70e2115bef6'


def main():
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        raise SystemExit('This installer pins macOS arm64 tooling; install Helm 3.19.0 manually on other hosts')
    folder = KUBE_ROOT / '.tools'
    folder.mkdir(exist_ok=True)
    helm = folder / 'helm'
    if not helm.exists():
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'helm.tar.gz'
            urllib.request.urlretrieve('https://get.helm.sh/helm-v'+HELM_VERSION+'-darwin-arm64.tar.gz', archive)
            if hashlib.sha256(archive.read_bytes()).hexdigest() != HELM_SHA256:
                raise SystemExit('Helm archive checksum mismatch')
            with tarfile.open(archive) as source:
                helm.write_bytes(source.extractfile('darwin-arm64/helm').read())
            helm.chmod(0o755)
    subprocess.run([sys.executable, '-m', 'venv', str(folder / 'venv')], check=True)
    subprocess.run([str(folder/'venv/bin/python'), '-m', 'pip', 'install', '-r', str(KUBE_ROOT/'requirements.txt')], check=True)
    kube = ['kubectl', '--context', 'docker-desktop']
    kindnet = subprocess.run(kube+['-n','kube-system','get','daemonset/kindnet','-o','json'], capture_output=True, text=True)
    if kindnet.returncode == 0:
        image = json.loads(kindnet.stdout)['spec']['template']['spec']['containers'][0]['image']
        if image != 'docker.io/kindest/kindnetd:v20260528-9350166c':
            raise SystemExit('Unknown kindnet version: verify built-in NetworkPolicy before installing another engine')
        extra = subprocess.run(kube+['-n','kube-system','get','daemonset/kube-network-policies'], capture_output=True)
        if extra.returncode == 0:
            raise SystemExit('Two NetworkPolicy engines detected; follow the approved kindnet recovery runbook first')
        print('Using built-in kindnet NetworkPolicy; no second controller installed')
    else:
        raise SystemExit('Expected Docker Desktop kindnet is missing; verify the CNI before continuing')
    # Use the charts shipped with the exact release, independent of index lag.
    release_folder = folder / 'istio-1.31.2'
    if not release_folder.exists():
        url = 'https://github.com/istio/istio/releases/download/1.31.2/istio-1.31.2-osx-arm64.tar.gz'
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != 'c02f9390ff8300b550c7b13230feae80adebe31aa7136f2930a1918502c626d3':
            raise SystemExit('Istio release checksum mismatch')
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            for member in archive.getmembers():
                target = (folder / member.name).resolve()
                if not target.is_relative_to(folder.resolve()) or member.issym() or member.islnk():
                    raise SystemExit('Unsafe Istio archive member')
            archive.extractall(folder)
        (folder / 'istioctl').write_bytes((release_folder / 'bin/istioctl').read_bytes())
        (folder / 'istioctl').chmod(0o755)
    command = [str(helm), '--kube-context', 'docker-desktop']
    for name,url in [('istio','https://istio-release.storage.googleapis.com/charts'),('hashicorp','https://helm.releases.hashicorp.com')]:
        subprocess.run(command+['repo','add',name,url,'--force-update'],check=True)
    for release,chart in [('istio-base','base'),('istiod','istiod')]:
        subprocess.run(command+['upgrade','--install',release,str(release_folder/'manifests/charts'/('istio-control/istio-discovery' if chart=='istiod' else chart)),
            '-n','istio-system','--create-namespace','--take-ownership','-f',str(KUBE_ROOT/'istio.yaml'),
            '--wait','--timeout','180s'],check=True)


if __name__ == '__main__':
    main()
