#!/usr/bin/env bash
set -euo pipefail

for service in auth user lobby matchmaking game realtime; do
    password_var="${service^^}_DB_PASSWORD"
    export SERVICE_DB_PASSWORD="${!password_var}"
    # Read the secret from the environment: never interpolate it into SQL text.
    psql --username postgres --dbname postgres --set=ON_ERROR_STOP=1 --set=service="$service" <<'SQL'
\getenv password SERVICE_DB_PASSWORD
CREATE ROLE :"service" LOGIN PASSWORD :'password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE :"service" OWNER :"service";
REVOKE ALL ON DATABASE :"service" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"service" TO :"service";
SQL
done
unset SERVICE_DB_PASSWORD
