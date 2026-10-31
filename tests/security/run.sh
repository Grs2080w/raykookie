#!/bin/sh
# Roda os testes de segurança (backend Java + site Next.js) contra instâncias isoladas.
# Precisa de Docker (Testcontainers) e Node. Nunca aponta para a produção.
set -eu

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

echo "[seguranca] site (cabeçalhos)..."
node --test "$ROOT/tests/security/frontend-headers.test.mjs" "$ROOT/tests/security/gestao-routes.test.mjs"

echo "[seguranca] backend (SQL, headers/CORS, autenticação, abusos, histórico)..."
cd "$ROOT/backend-java"
./mvnw -pl app -am verify \
  -Djacoco.skip=true -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='Security*IT,AuditLogIT,AccessMatrixIT,AbuseIT' -Dfailsafe.failIfNoSpecifiedTests=false

echo "[seguranca] tudo passou."
