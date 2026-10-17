#!/usr/bin/env python3
"""Generate private local credentials and a CA without replacing existing files."""

import os
from pathlib import Path
import secrets
import subprocess


def main():
    root = Path(__file__).resolve().parents[1]
    env_file = root / "infra/local/.env"
    if not env_file.exists():
        keys = ["POSTGRES_PASSWORD", "REDIS_PASSWORD", "NATS_PASSWORD"]
        keys += [name.upper() + "_DB_PASSWORD" for name in
                 ("auth", "user", "lobby", "matchmaking", "game", "realtime")]
        descriptor = os.open(env_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            output.writelines(key + "=" + secrets.token_hex(24) + "\n" for key in keys)
    ca_dir = root / "infra/local/certs"
    ca_dir.mkdir(mode=0o700, exist_ok=True)
    key, certificate = ca_dir / "ca.key", ca_dir / "ca.crt"
    if key.exists() != certificate.exists():
        raise SystemExit("Incomplete CA; preserve existing files and repair manually.")
    if not key.exists():
        previous_umask = os.umask(0o077)
        try:
            subprocess.run([
                "openssl", "req", "-x509", "-newkey", "rsa:3072", "-nodes",
                "-keyout", str(key), "-out", str(certificate), "-days", "365",
                "-subj", "/CN=tictactoe local development CA",
                "-addext", "basicConstraints=critical,CA:TRUE",
                "-addext", "keyUsage=critical,keyCertSign,cRLSign",
            ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        finally:
            os.umask(previous_umask)
    print("Local credentials and CA ready; secret values are not printed.")


if __name__ == "__main__":
    main()
