#!/usr/bin/env bash
# Instala e inicia o servico. Uso:  sudo bash deploy/install.sh
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Falta o .env. Rode antes:  sudo bash scripts/setup-db.sh"; exit 1; }
[ -d .next ] || { echo "Falta o build. Rode antes:  npm run build"; exit 1; }
install -m 644 deploy/rayka-estoque.service /etc/systemd/system/rayka-estoque.service
systemctl daemon-reload
systemctl enable --now rayka-estoque
sleep 2
systemctl --no-pager status rayka-estoque | head -12
echo "App em http://localhost:10005"
