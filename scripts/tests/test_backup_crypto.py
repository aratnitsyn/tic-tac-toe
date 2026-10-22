"""Exercise real OpenSSL encryption and reject authenticated-file/archive tampering."""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PYTHON = ROOT / 'infra/kubernetes/.tools/venv/bin/python'


class BackupCryptoTest(unittest.TestCase):
    def run_check(self, body):
        prefix = "import sys; sys.path.insert(0, 'infra/kubernetes/tool'); import backup\n"
        subprocess.run([str(PYTHON), '-c', prefix + body], cwd=ROOT, check=True, capture_output=True)

    def test_roundtrip_uses_random_salt(self):
        self.run_check('''
master = bytes(range(32))
plain = b'non-secret backup fixture' * 100
first, second = backup.encode(plain, master), backup.encode(plain, master)
assert first != second
assert plain not in first
assert backup.decode(first, master) == plain
assert backup.decode(second, master) == plain
''')

    def test_tampering_and_wrong_key_fail_before_decryption(self):
        self.run_check('''
from unittest.mock import patch
master = bytes(range(32))
original = backup.encode(b'non-secret fixture', master)
tampered = original[:-1] + bytes([original[-1] ^ 1])
for payload, key in [(tampered, master), (original, bytes(reversed(master)))]:
    with patch.object(backup, 'cipher', side_effect=AssertionError('must not decrypt')):
        try:
            backup.decode(payload, key)
        except RuntimeError as error:
            assert 'authentication failed' in str(error)
        else:
            raise AssertionError('tampered backup accepted')
''')

    def test_archive_cannot_escape_restore_directory(self):
        self.run_check('''
import io, tarfile
backup.safe_archive(backup.tar({'data/safe': b'fixture'}))
for name in ['/outside', '../outside']:
    try:
        backup.safe_archive(backup.tar({name:b'fixture'}))
    except RuntimeError:
        pass
    else:
        raise AssertionError('unsafe path accepted')
output = io.BytesIO()
with tarfile.open(fileobj=output, mode='w') as archive:
    entry = tarfile.TarInfo('data/link')
    entry.type, entry.linkname = tarfile.SYMTYPE, '/outside'
    archive.addfile(entry)
try:
    backup.safe_archive(output.getvalue())
except RuntimeError:
    pass
else:
    raise AssertionError('symlink accepted')
''')
