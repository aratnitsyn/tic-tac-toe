#!/usr/bin/env python3
"""Issue a local leaf from the existing development CA. Never installs trust."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
folder = root / 'infra/local/certs'
key, cert = folder / 'web.key', folder / 'web.crt'
if key.exists() != cert.exists():
    raise SystemExit('Incomplete Web certificate; preserve existing files and repair manually.')
if not key.exists():
    if not (folder / 'ca.key').exists():
        raise SystemExit('Run python3 scripts/init-local.py first.')
    old = os.umask(0o077)
    try:
        with tempfile.TemporaryDirectory() as tmp:
            request = Path(tmp) / 'web.csr'
            extension = Path(tmp) / 'extension.cnf'
            extension.write_text('subjectAltName=IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
            for command in [
                ['openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(key), '-out', str(request), '-subj', '/CN=127.0.0.1'],
                ['openssl', 'x509', '-req', '-in', str(request), '-CA', str(folder / 'ca.crt'), '-CAkey', str(folder / 'ca.key'), '-set_serial', '0x' + os.urandom(16).hex(), '-out', str(cert), '-days', '180', '-extfile', str(extension)],
            ]:
                subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    finally:
        os.umask(old)
print('Web certificate ready; CA trust must be configured explicitly in the browser.')
