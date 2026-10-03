#!/usr/bin/env bash
# Instala dependências, aplica migrations e gera o build do backend e do frontend. Uso: bash infra/build.sh
set -euo pipefail
cd "$(dirname "$0")/.."
echo "==> backend"
( cd backend && npm ci --no-audit --no-fund && npm run migrate && npm run build )
echo "==> frontend"
( cd frontend && npm ci --no-audit --no-fund && npm run build )
echo "Build pronto. Para subir: sudo bash infra/install.sh"
