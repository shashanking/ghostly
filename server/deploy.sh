#!/usr/bin/env bash
# Idempotent. Run from /opt/hostedapps/ghostly on the VPS.
set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE=.env
if [ ! -f "$ENV_FILE" ]; then
  echo "creating $ENV_FILE with fresh secrets"
  cat > "$ENV_FILE" <<ENV
DOMAIN=skf.npf.mybluehost.me
GOOGLE_CLIENT_ID=736699818889-jio6642o3pl2c3mnok99ebvasjf2goro.apps.googleusercontent.com
PG_SUPER_PASSWORD=$(openssl rand -hex 24)
API_DB_PASSWORD=$(openssl rand -hex 24)
RAMAN_DB_PASSWORD=$(openssl rand -hex 24)
SESSION_SECRET=$(openssl rand -hex 48)
ENV
  chmod 600 "$ENV_FILE"
fi
set -a; source "$ENV_FILE"; set +a

echo "== bringing up ghostly-pg + ghostly-api"
docker compose -f docker-compose.ghostly.yaml --env-file "$ENV_FILE" up -d --build

echo "== waiting for postgres"
for i in $(seq 1 30); do
  docker exec ghostly-pg pg_isready -U ghostly -d ghostly >/dev/null 2>&1 && break
  sleep 2
done

echo "== roles (created once; passwords refreshed from .env every run)"
docker exec -i ghostly-pg psql -v ON_ERROR_STOP=1 -U ghostly -d ghostly <<SQL
do \$\$ begin
  if not exists (select 1 from pg_roles where rolname = 'ghostly_api') then create role ghostly_api login; end if;
  if not exists (select 1 from pg_roles where rolname = 'raman') then create role raman login; end if;
end \$\$;
alter role ghostly_api password '${API_DB_PASSWORD}';
alter role raman password '${RAMAN_DB_PASSWORD}';
SQL

echo "== migrations (as superuser, so the schemas exist before grants)"
docker compose -f docker-compose.ghostly.yaml --env-file "$ENV_FILE" run --rm \
  -e DATABASE_URL="postgres://ghostly:${PG_SUPER_PASSWORD}@ghostly-pg:5432/ghostly" ghostly-api node src/migrate.js

echo "== grants: api owns app.*, reads content.*; raman owns content.*, reads aggregates only"
docker exec -i ghostly-pg psql -v ON_ERROR_STOP=1 -U ghostly -d ghostly <<'SQL'
grant usage on schema app, content, public to ghostly_api, raman;

grant select, insert, update, delete on all tables in schema app to ghostly_api;
grant usage, select on all sequences in schema app to ghostly_api;
grant select on all tables in schema content to ghostly_api;
alter default privileges in schema app grant select, insert, update, delete on tables to ghostly_api;
alter default privileges in schema content grant select on tables to ghostly_api;

grant select, insert, update, delete on all tables in schema content to raman;
grant usage, select on all sequences in schema content to raman;
alter default privileges in schema content grant select, insert, update, delete on tables to raman;
-- the editor sees numbers about users, never a user
revoke all on all tables in schema app from raman;
grant select on app.aggregates, app.event_mix_7d to raman;
SQL

echo "== api restart so it picks up the grants"
docker compose -f docker-compose.ghostly.yaml --env-file "$ENV_FILE" restart ghostly-api >/dev/null
sleep 3
docker exec ghostly-api wget -qO- http://127.0.0.1:3000/v1/health && echo
echo "== done"
