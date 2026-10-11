#!/usr/bin/env bash
# Apaga containers do Testcontainers que sobraram de execuções antigas dos testes (e os volumes
# anônimos deles). Os testes já se limpam sozinhos (ver PostgresTestBase e ContainerCleanup);
# isto é só para restos de antes da correção ou de uma máquina que caiu no meio da suíte.
#
# Cuidado: apaga também os containers de uma suíte que esteja rodando agora em outro terminal.
set -euo pipefail

# ── 1. Containers marcados pelo Testcontainers (Postgres e Ryuk de todas as sessões)
ids="$(docker ps -aq --filter label=org.testcontainers=true)"
if [[ -z "$ids" ]]; then
  echo "Nenhum container do Testcontainers."
  exit 0
fi

# ── 2. Remove à força, levando os volumes anônimos
# shellcheck disable=SC2086
docker rm -fv $ids
echo "Containers do Testcontainers removidos."
