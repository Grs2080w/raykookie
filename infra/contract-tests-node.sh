#!/usr/bin/env bash
# Roda a suíte de contrato do backend-java (Tarefa 3.0) contra o backend Node atual, num banco descartável.
#
#   1. sobe um postgres:17.11 descartável (fuso America/Sao_Paulo) com o banco rayka_contract_test;
#   2. aplica as migrations do Node (backend/scripts/migrate.mjs);
#   3. sobe o backend/ já buildado (npm ci && npm run build) apontado para esse banco e para o Mercado
#      Pago falso da suíte, com TODA credencial real apagada (o Next carregaria o backend/.env de produção
#      para as variáveis que não existirem no ambiente);
#   4. roda ./mvnw -pl contract-tests verify com CONTRACT_TARGET=node;
#   5. derruba o servidor e o banco, dê certo ou não.
#
# Pré-requisitos: Docker, Node 22, JDK 25 no JAVA_HOME. Portas: PG 54332, API 10099, MP falso 18080.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pg_port="${CONTRACT_PG_PORT:-54332}"
api_port="${CONTRACT_API_PORT:-10099}"
mp_port="${CONTRACT_MP_FAKE_PORT:-18080}"
container="rk-contract-pg-$$"
db_url="postgres://rayka_test:rayka_test@127.0.0.1:${pg_port}/rayka_contract_test"
server_pid=""

cleanup() {
  if [ -n "$server_pid" ]; then kill "$server_pid" 2>/dev/null || true; fi
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ ! -d "$repo/backend/.next" ]; then
  echo "Falta o build do backend: cd backend && npm ci && npm run build" >&2
  exit 1
fi

echo "[contrato] subindo o Postgres descartável..."
docker run -d --name "$container" -p "127.0.0.1:${pg_port}:5432" \
  -e POSTGRES_USER=rayka_test -e POSTGRES_PASSWORD=rayka_test -e POSTGRES_DB=rayka_contract_test \
  -e TZ=America/Sao_Paulo -e PGTZ=America/Sao_Paulo \
  postgres:17.11 postgres -c timezone=America/Sao_Paulo >/dev/null
# Pela rede (TCP): durante a inicialização da imagem o Postgres temporário só escuta no socket local,
# então um pg_isready comum responderia cedo demais.
for _ in $(seq 1 60); do
  docker exec "$container" psql -h 127.0.0.1 -U rayka_test -d rayka_contract_test -tAc 'SELECT 1' >/dev/null 2>&1 && break
  sleep 1
done

echo "[contrato] aplicando as migrations do Node..."
(cd "$repo/backend" && DATABASE_URL="$db_url" node scripts/migrate.mjs >/dev/null)

echo "[contrato] subindo o backend Node na porta ${api_port}..."
(
  cd "$repo/backend"
  exec env PORT="$api_port" HOST=127.0.0.1 NODE_ENV=production DATABASE_URL="$db_url" \
    SHOP_OWNER=rayka SITE_URL="http://127.0.0.1:${api_port}" COOKIE_SECURE=false \
    MP_API_BASE="http://127.0.0.1:${mp_port}" MP_ACCESS_TOKEN=TEST-contrato MP_PUBLIC_KEY=TEST-contrato-chave \
    MP_WEBHOOK_SECRET=segredo-webhook-contrato MP_CLIENT_ID= MP_CLIENT_SECRET= \
    JOBS_SECRET= FIREBASE_SERVICE_ACCOUNT_PATH= GOOGLE_CLIENT_ID= GOOGLE_CLIENT_SECRET= \
    DOWNLOAD_SECRET=segredo-de-download-do-contrato-0123456789 DOWNLOADS_DIR="$repo/backend/downloads" \
    PAY_LINK_PAYER_EMAIL= FRONTEND_DIR= \
    node server.mjs
) > "${TMPDIR:-/tmp}/rk-contract-node.log" 2>&1 &
server_pid=$!
for _ in $(seq 1 60); do
  curl -fsS "http://127.0.0.1:${api_port}/api/health" >/dev/null 2>&1 && break
  sleep 1
done

echo "[contrato] rodando a suíte contra o Node..."
cd "$repo/backend-java"
CONTRACT_TARGET=node \
CONTRACT_BASE_URL="http://127.0.0.1:${api_port}" \
CONTRACT_DB_URL="jdbc:postgresql://127.0.0.1:${pg_port}/rayka_contract_test?user=rayka_test&password=rayka_test" \
CONTRACT_MP_FAKE_PORT="$mp_port" \
CONTRACT_SHOP_OWNER=rayka \
  ./mvnw -B -ntp -pl contract-tests verify "$@"
