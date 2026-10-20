#!/usr/bin/env python3
"""Lint all local Helm charts and emit their parsed resources for boundary tests."""
import argparse
import json
from common import KUBE_ROOT, documents, helm


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    result = []
    for name in ('platform', 'storage', 'mesh', 'secret', 'observation'):
        helm('lint', str(KUBE_ROOT / 'chart' / name), '-f', str(KUBE_ROOT / 'values.yaml'), capture_output=True)
        result.extend(documents(name))
    if args.json:
        print(json.dumps(result))
    else:
        print('Helm lint/template passed for platform, storage, mesh, secret and observation')


if __name__ == '__main__':
    main()
