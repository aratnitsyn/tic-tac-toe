#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose=(docker compose --env-file "$repo_root/infra/local/.env" -f "$repo_root/infra/local/compose.yml")
"${compose[@]}" config --quiet
for service in auth user lobby matchmaking game realtime; do
    "${compose[@]}" exec -T postgres bash -e -s -- "$service" <<'SH'
service="$1"
password_var="${service^^}_DB_PASSWORD"
export PGPASSWORD="${!password_var}"
psql -h 127.0.0.1 -U "$service" -d "$service" -v ON_ERROR_STOP=1 -Atqc 'SELECT current_user' | grep -qx "$service"
other=auth
if [[ "$service" == auth ]]; then other=game; fi
if psql -h 127.0.0.1 -U "$service" -d "$other" -Atqc 'SELECT 1' >/dev/null 2>&1; then
    echo "Cross-database access unexpectedly permitted" >&2
    exit 1
fi
SH
done
"${compose[@]}" exec -T redis sh -ec 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping | grep -q PONG'
"${compose[@]}" exec -T nats sh -ec 'wget -q -O /dev/null http://127.0.0.1:8222/healthz'
echo "Local health and database role isolation checks passed"
