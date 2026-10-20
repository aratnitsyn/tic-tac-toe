#!/usr/bin/env python3
"""Build Linux service images and import them into Docker Desktop's kind node."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
from render import KUBE_ROOT, ROOT, JVM


def run(command, **kwargs):
    subprocess.run(command, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-compile', action='store_true', help='Reuse already built artifacts')
    parser.add_argument('--load', action='store_true', help='Import into the docker-desktop node')
    args = parser.parse_args()
    values = json.loads((KUBE_ROOT / 'values.json').read_text())
    arch = 'arm64' if platform.machine() == 'arm64' else 'amd64'
    env = {**os.environ, 'CGO_ENABLED': '0', 'GOOS': 'linux', 'GOARCH': arch}
    go_service = ROOT / 'services/matchmaking'
    helper = KUBE_ROOT / '.local/bootstrap-streams'
    helper.parent.mkdir(exist_ok=True)
    if not args.skip_compile:
        run(['go', 'build', '-o', str(helper), './cmd/bootstrap-streams'], cwd=go_service, env=env)
    for service in sorted(JVM) + ['matchmaking', 'realtime']:
        directory = ROOT / 'services' / service
        if not args.skip_compile:
            if service in JVM:
                run([str(directory / 'gradlew'), '-p', str(directory), 'bootJar', '--no-daemon'])
            else:
                run(['go', 'build', '-o', str(directory / ('build/linux-' + arch)), '.'], cwd=directory, env=env)
        with tempfile.TemporaryDirectory() as tmp:
            context = Path(tmp)
            if service in JVM:
                jars = [p for p in (directory / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
                if len(jars) != 1:
                    raise SystemExit('Expected exactly one bootJar for ' + service)
                shutil.copyfile(jars[0], context / 'application.jar')
                dockerfile = KUBE_ROOT / 'Dockerfile.jvm'
            else:
                shutil.copyfile(directory / ('build/linux-' + arch), context / 'service')
                shutil.copyfile(helper, context / 'bootstrap-streams')
                dockerfile = KUBE_ROOT / 'Dockerfile.go'
            image = f"{values['imagePrefix']}/{service}:{values['imageTag']}"
            run(['docker', 'build', '--platform', 'linux/' + arch, '-t', image, '-f', str(dockerfile), str(context)])
            if args.load:
                # Docker Desktop's kind provisioner has a separate containerd image store.
                # An explicit node name avoids importing into unrelated Docker containers.
                archive = context / 'image.tar'
                run(['docker', 'save', '-o', str(archive), image])
                with archive.open('rb') as source:
                    run(['docker', 'exec', '-i', 'desktop-control-plane', 'ctr', '-n', 'k8s.io', 'images', 'import', '-'], stdin=source)


if __name__ == '__main__':
    main()
