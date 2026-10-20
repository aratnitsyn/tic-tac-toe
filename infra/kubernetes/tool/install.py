#!/usr/bin/env python3
"""Install pinned local Istio tooling and the docker-desktop prerequisites."""
import hashlib
import io
import platform
import subprocess
import tarfile
import urllib.request
from render import KUBE_ROOT

VERSION = '1.31.2'
# Release asset checksum verified when the local profile was introduced.
CHECKSUM = 'b5f57049a6fe4b8ac39f13a9f28fcdd2a8cbfac9a086be56d9f09f490765960f'


def main():
    if (platform.system(), platform.machine()) != ('Darwin', 'arm64'):
        raise SystemExit('This tool installer is pinned for macOS arm64. Install matching istioctl 1.31.2 explicitly on other hosts.')
    folder = KUBE_ROOT / '.tools'
    folder.mkdir(exist_ok=True)
    url = f'https://github.com/istio/istio/releases/download/{VERSION}/istioctl-{VERSION}-osx-arm64.tar.gz'
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != CHECKSUM:
        raise SystemExit('istioctl checksum mismatch')
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        member = next(item for item in archive.getmembers() if item.name.rsplit('/', 1)[-1] == 'istioctl')
        (folder / 'istioctl').write_bytes(archive.extractfile(member).read())
    (folder / 'istioctl').chmod(0o755)
    subprocess.run(['kubectl', '--context', 'docker-desktop', 'apply', '-f', str(KUBE_ROOT / 'vendor/network-policy.yaml')], check=True)
    subprocess.run(['kubectl', '--context', 'docker-desktop', '-n', 'kube-system', 'rollout', 'status',
                    'daemonset/kube-network-policies', '--timeout=180s'], check=True)
    subprocess.run([str(folder / 'istioctl'), '--context', 'docker-desktop', 'install',
                    '-f', str(KUBE_ROOT / 'istio.yaml'), '-y'], check=True)


if __name__ == '__main__':
    main()
