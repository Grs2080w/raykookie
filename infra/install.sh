#!/usr/bin/env bash
# Instala/atualiza o serviço único (API + site na porta 10005) e reinicia. Uso: sudo bash infra/install.sh
# Rode antes: bash infra/build.sh
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d backend/.next ] && [ -d frontend/.next ] || { echo "Faltam os builds. Rode antes:  bash infra/build.sh"; exit 1; }
[ -f backend/.env ] || { echo "Falta backend/.env (veja backend/.env.example)."; exit 1; }
# serviços das versões anteriores saem de cena (o único serviço agora é o rayka)
for old in rayka-estoque rayka-backend rayka-frontend; do
  if systemctl list-unit-files | grep -q "^$old.service"; then systemctl disable --now "$old" || true; fi
done
rm -f /etc/systemd/system/rayka-backend.service /etc/systemd/system/rayka-frontend.service
install -m 644 infra/rayka.service /etc/systemd/system/rayka.service
systemctl daemon-reload
systemctl enable rayka
systemctl restart rayka
for i in $(seq 1 30); do curl -fs http://127.0.0.1:10005/api/health >/dev/null && break; sleep 1; done
echo "api : $(curl -s http://127.0.0.1:10005/api/health)"
echo "site: HTTP $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:10005/)"
systemctl --no-pager status rayka | grep -E "●|Active"
