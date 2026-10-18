#!/usr/bin/env bash
# Sobe (ou recria) o container da API Java de produção, "raykookie-api", com a imagem pedida.
#   bash infra/ci/run-api.sh <imagem>
# O proxy da máquina (rproxy) liga o host de API_BASE (config/urls.json; hoje api.raykookie.com.br) ao container "raykookie-api" na porta 20010.
# Variáveis: ENV_FILE (banco e Mercado Pago, o mesmo do Node), JAVA_ENV_FILE (só do Java: DOWNLOAD_SECRET,
# MP_PUBLIC_KEY, PAYMENTS_CARD_ENABLED…), DOWNLOADS_DIR e DATA_DIR; os padrões são os caminhos do servidor.
set -euo pipefail

# ── 1. Configuração
IMAGE="${1:?imagem}"
ENV_FILE="${ENV_FILE:-/home/g_snts/works/raykookie/backend/.env.docker}"
JAVA_ENV_FILE="${JAVA_ENV_FILE:-/home/g_snts/.raykookie/java.env}"
DOWNLOADS_DIR="${DOWNLOADS_DIR:-/home/g_snts/works/raykookie/downloads}"
DATA_DIR="${DATA_DIR:-/home/g_snts/.raykookie}"
for f in "$ENV_FILE" "$JAVA_ENV_FILE"; do
  [[ -f "$f" ]] || { echo "Arquivo de variáveis não encontrado: $f" >&2; exit 1; }
done

# ── 2. Endereço público do site (config/urls.json) — vale mais que o SITE_URL dos arquivos de variáveis
source "$(dirname "${BASH_SOURCE[0]}")/../urls.sh"
SITE_URL="$(url_of SITE_URL)"

# ── 3. Recria o container (rede do host: o Postgres escuta só em 127.0.0.1)
docker rm -f raykookie-api >/dev/null 2>&1 || true
docker run -d --name raykookie-api --network host --restart unless-stopped \
  --env-file "$ENV_FILE" --env-file "$JAVA_ENV_FILE" \
  -e PORT=20010 -e SITE_URL="$SITE_URL" -e SPRING_PROFILES_ACTIVE=prod -e DOWNLOADS_DIR=/app/downloads \
  --health-cmd "wget -qO- http://127.0.0.1:20010/api/health || exit 1" \
  --health-interval 30s --health-timeout 3s \
  -v "$DOWNLOADS_DIR:/app/downloads" \
  -v "$DATA_DIR:$DATA_DIR:ro" \
  "$IMAGE" >/dev/null
echo "raykookie-api ($IMAGE) iniciado na porta 20010"
