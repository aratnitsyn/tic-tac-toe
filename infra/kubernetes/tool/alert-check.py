#!/usr/bin/env python3
"""Temporarily stop User, observe a real alert, restore its replica count in finally."""
import json
import time
from common import kubectl, values
from importlib.util import spec_from_file_location, module_from_spec
from pathlib import Path

spec = spec_from_file_location('observation_check', Path(__file__).with_name('observation-check.py'))
observation = module_from_spec(spec)
spec.loader.exec_module(observation)


def main():
    profile = values()
    namespace = profile['namespace']
    result = kubectl('-n', namespace, 'get', 'deployment/user', '-o', 'json', capture_output=True, text=True)
    original = json.loads(result.stdout)['spec'].get('replicas', 1)
    if original < 1:
        raise RuntimeError('User must be running before fault injection')
    with observation.tunnel(profile['observation']['namespace'], 'prometheus', 18990, 9090):
        observation.wait(lambda: observation.get(18990, '/api/v1/targets')['status'] == 'success')
        def firing():
            return [x for x in observation.get(18990, '/api/v1/alerts')['data']['alerts']
                    if x['labels'].get('alertname') == 'BackendUnavailable'
                    and x['labels'].get('job') == 'user' and x['state'] == 'firing']
        observation.wait(lambda: not firing(), timeout=60)
        observation.wait(lambda: any(t['labels'].get('job') == 'user' and t['health'] == 'up'
                                    for t in observation.get(18990, '/api/v1/targets')['data']['activeTargets']))
        started = time.monotonic()
        try:
            kubectl('-n', namespace, 'scale', 'deployment/user', '--replicas=0')
            observation.wait(firing, timeout=90)
            print('PASS: real User outage triggers BackendUnavailable after %.1fs' % (time.monotonic()-started), flush=True)
        finally:
            kubectl('-n', namespace, 'scale', 'deployment/user', '--replicas='+str(original))
            kubectl('-n', namespace, 'rollout', 'status', 'deployment/user', '--timeout=180s')
        observation.wait(lambda: not firing(), timeout=60)
        observation.wait(lambda: any(t['labels'].get('job') == 'user' and t['health'] == 'up'
                                    for t in observation.get(18990, '/api/v1/targets')['data']['activeTargets']))
        print('PASS: restored replica count, healthy scrape and resolved alert', flush=True)


if __name__ == '__main__':
    main()
