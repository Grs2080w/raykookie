#!/usr/bin/env bash
# Roda a suíte de contrato do backend-java contra o PRÓPRIO backend Java, num banco montado como o de
# produção: as migrations do Node (001–021) e depois o Java no perfil "prod" (guarda do esquema + Flyway
# com baseline 21 + V22+), exatamente como será o corte.
#
#   1. sobe um postgres:17.11 descartável com o banco rayka_contract_test e aplica as migrations do Node;
#   2. cria a dona (SHOP_OWNER=rayka): sem ela o Java não sobe (G24);
#   3. empacota e sobe o backend-java (app/target/raykookie-app.jar) apontado para o banco e para o
#      Mercado Pago falso da suíte;
#   4. roda ./mvnw -pl contract-tests verify com CONTRACT_TARGET=java;
#   5. derruba tudo.
#
# Pré-requisitos: Docker, Node 22 (para as migrations), JDK 25. Portas: PG 54333, API 10098, MP falso 18081.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pg_port="${CONTRACT_PG_PORT:-54333}"
api_port="${CONTRACT_API_PORT:-10098}"
mp_port="${CONTRACT_MP_FAKE_PORT:-18081}"
container="rk-contract-java-pg-$$"
db_url="postgres://rayka_test:rayka_test@127.0.0.1:${pg_port}/rayka_contract_test"
owner_hash='scrypt$00112233445566778899aabbccddeeff$216992f0b9009b3af7a1ae842f0b167c45e4c876e89b9ebea0aae61d62c690a42dea16df88ba4d801cf641c80e33edeacb7d65df1e739162cb307c6536e905ff'
server_pid=""

cleanup() {
  if [ -n "$server_pid" ]; then kill "$server_pid" 2>/dev/null || true; fi
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "[contrato] subindo o Postgres descartável..."
docker run -d --name "$container" -p "127.0.0.1:${pg_port}:5432" \
  -e POSTGRES_USER=rayka_test -e POSTGRES_PASSWORD=rayka_test -e POSTGRES_DB=rayka_contract_test \
  -e TZ=America/Sao_Paulo -e PGTZ=America/Sao_Paulo \
  postgres:17.11 postgres -c timezone=America/Sao_Paulo >/dev/null
for _ in $(seq 1 60); do
  docker exec "$container" psql -h 127.0.0.1 -U rayka_test -d rayka_contract_test -tAc 'SELECT 1' >/dev/null 2>&1 && break
  sleep 1
done

echo "[contrato] aplicando as migrations do Node e criando a dona..."
(cd "$repo/backend" && DATABASE_URL="$db_url" node scripts/migrate.mjs >/dev/null)
docker exec "$container" psql -h 127.0.0.1 -U rayka_test -d rayka_contract_test -qc \
  "INSERT INTO users (username, password_hash) VALUES ('rayka', '${owner_hash}');
   INSERT INTO settings (user_id) SELECT id FROM users WHERE username = 'rayka';" >/dev/null

echo "[contrato] empacotando o backend Java..."
cd "$repo/backend-java"
./mvnw -B -ntp -q -pl app -am package -DskipTests -Djacoco.skip=true

echo "[contrato] subindo o backend Java na porta ${api_port}..."
env PORT="$api_port" SPRING_PROFILES_ACTIVE=prod DATABASE_URL="$db_url" \
  SHOP_OWNER=rayka SITE_URL="http://localhost:${api_port}" COOKIE_SECURE=false \
  MP_API_BASE="http://127.0.0.1:${mp_port}" MP_ACCESS_TOKEN=TEST-contrato MP_PUBLIC_KEY=TEST-contrato-chave \
  MP_WEBHOOK_SECRET=segredo-webhook-contrato PAYMENTS_CARD_ENABLED=false JOBS_ENABLED=false \
  DOWNLOAD_SECRET=segredo-de-download-do-contrato-0123456789 DOWNLOADS_DIR="$repo/backend/downloads" \
  java -jar app/target/raykookie-app.jar > "${TMPDIR:-/tmp}/rk-contract-java.log" 2>&1 &
server_pid=$!
for _ in $(seq 1 90); do
  curl -fsS "http://127.0.0.1:${api_port}/actuator/health" >/dev/null 2>&1 && break
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "O backend Java não subiu; veja ${TMPDIR:-/tmp}/rk-contract-java.log" >&2
    exit 1
  fi
  sleep 1
done

echo "[contrato] rodando a suíte contra o Java..."
CONTRACT_TARGET=java \
CONTRACT_BASE_URL="http://127.0.0.1:${api_port}" \
CONTRACT_DB_URL="jdbc:postgresql://127.0.0.1:${pg_port}/rayka_contract_test?user=rayka_test&password=rayka_test" \
CONTRACT_MP_FAKE_PORT="$mp_port" \
CONTRACT_SHOP_OWNER=rayka \
  ./mvnw -B -ntp -pl contract-tests verify "$@"
