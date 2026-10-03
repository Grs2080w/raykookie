#!/usr/bin/env bash
# Cria role + banco do Rayka Estoque no Postgres local, grava .env e roda as migrations.
# Uso (uma vez):  sudo bash scripts/setup-db.sh
set -euo pipefail
cd "$(dirname "$0")/.."
OWNER="${SUDO_USER:-$(whoami)}"
DB=rayka_estoque; ROLE=rayka
if [ -f .env ] && grep -q '^DATABASE_URL=' .env; then echo ".env já existe; mantendo a senha atual."; PASS=$(sed -E 's#.*://[^:]+:([^@]+)@.*#\1#' <(grep '^DATABASE_URL=' .env));
else PASS=$(openssl rand -hex 16); fi
run_pg() { if [ "$(id -u)" = 0 ]; then runuser -u postgres -- psql -v ON_ERROR_STOP=1 "$@"; else sudo -u postgres psql -v ON_ERROR_STOP=1 "$@"; fi; }
run_pg -tc "SELECT 1 FROM pg_roles WHERE rolname='$ROLE'" | grep -q 1 \
  && run_pg -c "ALTER ROLE $ROLE WITH LOGIN PASSWORD '$PASS'" \
  || run_pg -c "CREATE ROLE $ROLE WITH LOGIN PASSWORD '$PASS'"
run_pg -tc "SELECT 1 FROM pg_database WHERE datname='$DB'" | grep -q 1 || run_pg -c "CREATE DATABASE $DB OWNER $ROLE"
if [ ! -f .env ]; then
  printf 'DATABASE_URL=postgres://%s:%s@localhost:5432/%s\nPORT=10005\nNODE_ENV=production\n' "$ROLE" "$PASS" "$DB" > .env
fi
chown "$OWNER":"$OWNER" .env 2>/dev/null || true; chmod 600 .env
echo "Banco pronto: $DB (role $ROLE). .env criado. Agora rode:  npm run migrate"
