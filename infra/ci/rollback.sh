#!/usr/bin/env bash
# Volta o site/API para a release anterior (ou para a pasta indicada) e reinicia.
#   bash infra/ci/rollback.sh [releases/AAAAMMDD-HHMMSS-sha]
# O banco NÃO volta: as migrations são aditivas, então o código antigo funciona no banco novo. Se precisar do banco
# de antes, use o backup em $RAYKA_ROOT/backups/pre-*.sql.gz (restaure com psql; avise antes, pois perde dados novos).
set -euo pipefail
ROOT="${RAYKA_ROOT:-/srv/rayka}"
SERVICE="${RAYKA_SERVICE:-rayka}"
SYSTEMCTL="${SYSTEMCTL:-sudo -n systemctl}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:10005/api/health}"
die() { echo "::error::$*" >&2; exit 1; }

target="${1:-$(cat "$ROOT/previous" 2>/dev/null || true)}"
[[ -n "$target" ]] || die "não há release anterior registrada (informe a pasta: bash infra/ci/rollback.sh $ROOT/releases/<nome>)"
[[ "$target" == /* ]] || target="$ROOT/$target"
target="$(readlink -f "$target")"
case "$target" in "$ROOT"/releases/*) ;; *) die "a pasta precisa estar em $ROOT/releases/" ;; esac
[[ -d "$target/backend/.next" && -d "$target/frontend/.next" ]] || die "release incompleta (sem build): $target"

cur="$(readlink -f "$ROOT/current" 2>/dev/null || true)"
[[ -d "$cur" ]] || cur=""
[[ "$cur" != "$target" ]] || die "essa release já é a que está no ar"
ln -sfn "$target" "$ROOT/current.tmp" && mv -Tf "$ROOT/current.tmp" "$ROOT/current"
[[ -n "$cur" ]] && printf '%s\n' "$cur" >"$ROOT/previous"
$SYSTEMCTL restart "$SERVICE"
for ((i = 0; i < 60; i++)); do
  if curl -fsS --max-time 3 "$HEALTH_URL" >/dev/null 2>&1; then echo "rollback concluído: $target no ar"; exit 0; fi
  sleep 1
done
die "o serviço não ficou saudável depois do rollback: verifique 'journalctl -u $SERVICE'"
