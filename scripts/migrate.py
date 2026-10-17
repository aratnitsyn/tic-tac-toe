#!/usr/bin/env python3
"""Render and apply immutable SQL migrations through psql, with no Python deps."""

import argparse
import hashlib
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ("auth", "user", "lobby", "matchmaking", "game", "realtime")
MIGRATION = re.compile(r"V([0-9]+)__([a-z0-9_]+)\.sql\Z")


def render(directory):
    migrations = []
    for path in directory.glob("*.sql"):
        match = MIGRATION.fullmatch(path.name)
        if not match:
            raise ValueError("Invalid migration filename: " + path.name)
        version = int(match.group(1))
        if version < 1:
            raise ValueError("Migration version must be positive")
        migrations.append((version, match.group(2), path))
    migrations.sort()
    if not migrations:
        raise ValueError("No migrations found")
    versions = [version for version, _, _ in migrations]
    if len(versions) != len(set(versions)):
        raise ValueError("Duplicate migration version")
    statements = [
        "\\set ON_ERROR_STOP on",
        "BEGIN;",
        "SELECT pg_advisory_xact_lock(hashtextextended(current_database() || ':migrations', 0));",
        "CREATE TABLE IF NOT EXISTS schema_history (version bigint PRIMARY KEY, "
        "description text NOT NULL, checksum char(64) NOT NULL, "
        "installed_at timestamptz NOT NULL DEFAULT clock_timestamp());",
        "DO $history$ BEGIN IF EXISTS (SELECT 1 FROM schema_history WHERE version NOT IN ("
        + ",".join(str(v) for v in versions)
        + ")) THEN RAISE EXCEPTION 'Database contains migrations absent from this release'; "
        "END IF; END $history$;",
    ]
    for version, description, path in migrations:
        sql = path.read_text()
        checksum = hashlib.sha256(path.read_bytes()).hexdigest()
        statements += [
            "DO $checksum$ BEGIN IF EXISTS (SELECT 1 FROM schema_history "
            f"WHERE version = {version} AND checksum <> '{checksum}') THEN "
            f"RAISE EXCEPTION 'Migration checksum mismatch for version {version}'; "
            "END IF; END $checksum$;",
            "DO $order$ BEGIN IF NOT EXISTS (SELECT 1 FROM schema_history "
            f"WHERE version = {version}) AND EXISTS (SELECT 1 FROM schema_history "
            f"WHERE version > {version}) THEN RAISE EXCEPTION 'Out-of-order migration'; "
            "END IF; END $order$;",
            f"SELECT NOT EXISTS (SELECT 1 FROM schema_history WHERE version = {version}) AS apply_migration \\gset",
            "\\if :apply_migration",
            # This runner owns transaction boundaries; migration files contain DDL/DML only.
            sql,
            "INSERT INTO schema_history(version, description, checksum) "
            f"VALUES ({version}, '{description}', '{checksum}');",
            "\\endif",
        ]
    statements += ["COMMIT;", ""]
    return "\n".join(statements)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=SERVICES)
    parser.add_argument("--directory", type=Path, help="Override directory for migration tests")
    parser.add_argument("--psql", action="store_true", help="Use host psql with standard PG* environment")
    args = parser.parse_args()
    directory = args.directory or ROOT / "services" / args.service / "db/migration"
    sql = render(directory)
    if args.psql:
        command = ["psql", "--no-psqlrc", "--set=ON_ERROR_STOP=1", "--dbname", args.service,
                   "--username", args.service]
    else:
        command = [
            "docker", "compose", "--env-file", str(ROOT / "infra/local/.env"),
            "-f", str(ROOT / "infra/local/compose.yml"), "exec", "-T", "postgres",
            "bash", "-ec",
            'password_var="${1^^}_DB_PASSWORD"; export PGPASSWORD="${!password_var}"; '
            'exec psql -X -h 127.0.0.1 -U "$1" -d "$1" -v ON_ERROR_STOP=1',
            "migrate", args.service,
        ]
    subprocess.run(command, input=sql, text=True, check=True)
    print("Migrations applied:", args.service)


if __name__ == "__main__":
    main()
