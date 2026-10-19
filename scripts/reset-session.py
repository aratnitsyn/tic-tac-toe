#!/usr/bin/env python3
"""Delete one account's session from the local Auth database by email."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def render(email):
    email = email.strip()
    if not email or len(email) > 254 or any(ord(c) < 33 or ord(c) > 126 for c in email) or '@' not in email:
        raise ValueError('Expected an ASCII email address')
    # Encode untrusted input as hex: quotes, backslashes and psql metacommands
    # can never become SQL. Normalization matches Auth's trimmed lowercase key.
    encoded = email.lower().encode('ascii').hex()
    return f"""\\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '15s';
DO $reset$
DECLARE target uuid; removed integer;
BEGIN
    SELECT user_id INTO target FROM accounts
    WHERE email_key = convert_from(decode('{encoded}', 'hex'), 'UTF8') FOR UPDATE;
    IF target IS NULL THEN
        RAISE EXCEPTION 'Account not found';
    END IF;
    PERFORM user_id FROM session_slots WHERE user_id = target FOR UPDATE;
    IF EXISTS (SELECT 1 FROM logout_operations WHERE user_id = target AND completed_at IS NULL) THEN
        RAISE EXCEPTION 'Logout is pending; wait for coordinated logout to complete';
    END IF;
    DELETE FROM session_slots WHERE user_id = target;
    GET DIAGNOSTICS removed = ROW_COUNT;
    -- Keep command tombstones: replaying an old login must not create a new session.
    UPDATE login_responses SET key_id = NULL, nonce = NULL, ciphertext = NULL
    WHERE user_id = target;
    RAISE NOTICE 'Session rows deleted: %', removed;
END $reset$;
COMMIT;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('email', help='Email of the local account to reset')
    args = parser.parse_args()
    try:
        sql = render(args.email)
    except ValueError as error:
        parser.error(str(error))
    command = [
        'docker', 'compose', '--env-file', str(ROOT / 'infra/local/.env'),
        '-f', str(ROOT / 'infra/local/compose.yml'), 'exec', '-T', 'postgres',
        'bash', '-ec',
        'export PGPASSWORD="$AUTH_DB_PASSWORD"; '
        'exec psql -X -h 127.0.0.1 -U auth -d auth -v ON_ERROR_STOP=1',
    ]
    try:
        subprocess.run(command, input=sql, text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        raise SystemExit('Session reset failed; inspect the error above. No successful reset is reported.')
    print('Session reset completed. Reload the browser and sign in again.')


if __name__ == '__main__':
    main()
