#!/usr/bin/env bash
# Recria o banco de TESTE descartável (porta 54329) e aplica as migrations. Nunca toca no banco de produção.
set -euo pipefail
B=/usr/lib/postgresql/17/bin; U=postgres://rayka_test@127.0.0.1:54329
$B/dropdb -h 127.0.0.1 -p 54329 -U rayka_test --if-exists rayka_test
$B/createdb -h 127.0.0.1 -p 54329 -U rayka_test rayka_test
cd "$(dirname "$0")/../backend" && DATABASE_URL="$U/rayka_test" npm run migrate >/dev/null && echo "banco de teste recriado"
