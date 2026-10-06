#!/usr/bin/env bash
# Deploy do site + API (um processo, porta 10005) em uma PASTA DE RELEASE nova, com troca atômica e rollback automático.
#
#   bash infra/ci/deploy.sh [PASTA_DO_CODIGO]            # padrão: a raiz do repositório
#   bash infra/ci/deploy.sh --bootstrap [PASTA]          # só monta a release e liga o "current" (sem backup, migration nem restart)
#
# Ordem (cada passo só roda se o anterior deu certo; nada toca no que está no ar antes do passo 6):
#   1 copia backend/frontend/shared  2 npm ci + build  3 backup do banco  4 migrations  5 troca o link "current"  6 reinicia + health check
#   Se o health check falhar: volta o link para a release anterior, reinicia e sai com erro (as migrations NÃO são desfeitas:
#   por convenção do projeto elas são aditivas, então o código antigo continua funcionando no banco novo).
#
# Layout (RAYKA_ROOT, padrão /srv/rayka):
#   releases/<data>-<sha>/{backend,frontend,shared}    current -> releases/…    previous (texto: release anterior)
#   shared/backend.env  (segredos do servidor; NUNCA no GitHub)    shared/downloads/  (APKs + manifest.json)    backups/
#
# Variáveis para testes: RAYKA_ROOT, RAYKA_SERVICE, SYSTEMCTL ("sudo -n systemctl"), HEALTH_URL, KEEP_RELEASES.
set -euo pipefail

ROOT="${RAYKA_ROOT:-/srv/rayka}"
SERVICE="${RAYKA_SERVICE:-rayka}"
SYSTEMCTL="${SYSTEMCTL:-sudo -n systemctl}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:10005/api/health}"
KEEP="${KEEP_RELEASES:-5}"
BOOTSTRAP=0
if [[ "${1:-}" == "--bootstrap" ]]; then BOOTSTRAP=1; shift; fi
SRC="$(cd "${1:-$(dirname "$0")/../..}" && pwd)"

log() { printf '\n==> %s\n' "$*"; }
die() { echo "::error::$*" >&2; exit 1; }
summary() { [[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && printf '%s\n' "$*" >>"$GITHUB_STEP_SUMMARY" || true; }

# ---- pré-requisitos ------------------------------------------------------------------------------------------
for c in rsync npm node curl gzip; do command -v "$c" >/dev/null || die "falta o comando: $c"; done
[[ $BOOTSTRAP == 1 ]] || command -v pg_dump >/dev/null || die "falta pg_dump (pacote postgresql-client): o backup do banco é obrigatório"
[[ -f "$ROOT/shared/backend.env" ]] || die "falta $ROOT/shared/backend.env (rode antes: sudo bash infra/ci/setup-server.sh)"
[[ -d "$ROOT/shared/downloads" ]] || die "falta $ROOT/shared/downloads"
for d in backend frontend shared; do [[ -d "$SRC/$d" ]] || die "pasta de código sem $d/: $SRC"; done
mkdir -p "$ROOT/releases" "$ROOT/backups"

SHA="${GITHUB_SHA:-$(git -C "$SRC" rev-parse HEAD 2>/dev/null || echo local)}"
REL="$ROOT/releases/$(date +%Y%m%d-%H%M%S)-${SHA:0:8}"
PREV="$(readlink -f "$ROOT/current" 2>/dev/null || true)"
[[ -d "$PREV" ]] || PREV="" # primeiro deploy: o link ainda não existe (readlink -f devolveria o caminho inexistente)

env_value() { grep -E "^$1=" "$ROOT/shared/backend.env" | head -1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//"; }

health() { # health <segundos>: espera a API e o site responderem
  local i
  for ((i = 0; i < $1; i++)); do
    if curl -fsS --max-time 3 "$HEALTH_URL" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}
swap() { ln -sfn "$1" "$ROOT/current.tmp" && mv -Tf "$ROOT/current.tmp" "$ROOT/current"; } # atômico

# ---- 1. código -----------------------------------------------------------------------------------------------
log "1/6 copiando o código para $REL"
mkdir -p "$REL"
cleanup() { # falha antes da troca: descarta a release pela metade (o que está no ar nunca foi tocado)
  local rc=$?
  if [[ $rc -ne 0 && -d "$REL" && "$(readlink -f "$ROOT/current" 2>/dev/null)" != "$REL" ]]; then echo "release descartada: $REL"; rm -rf "$REL"; fi
  exit "$rc"
}
trap cleanup EXIT
rsync -a --delete --exclude node_modules --exclude .next --exclude .next-test --exclude '.env' --exclude '.env.local' \
  "$SRC/backend" "$SRC/frontend" "$SRC/shared" "$REL/"
ln -s "$ROOT/shared/backend.env" "$REL/backend/.env"

# ---- 2. dependências + build (nada disso mexe no que está no ar) ----------------------------------------------
log "2/6 instalando dependências e compilando"
export NEXT_TELEMETRY_DISABLED=1
(cd "$REL/backend" && npm ci --no-audit --no-fund && npm run build)
(cd "$REL/frontend" && npm ci --no-audit --no-fund && npm run build)

if [[ $BOOTSTRAP == 0 ]]; then
  # ---- 3. backup do banco ------------------------------------------------------------------------------------
  log "3/6 backup do banco"
  DB="$(env_value DATABASE_URL)"; [[ -n "$DB" ]] || die "DATABASE_URL não encontrada em $ROOT/shared/backend.env"
  BK="$ROOT/backups/pre-$(date +%Y%m%d-%H%M%S)-${SHA:0:8}.sql.gz"
  pg_dump "$DB" | gzip >"$BK"
  gzip -t "$BK" && [[ $(stat -c %s "$BK") -gt 200 ]] || { rm -f "$BK"; die "backup do banco inválido: nada foi alterado"; }
  echo "backup: $BK ($(du -h "$BK" | cut -f1))"
  ls -1t "$ROOT"/backups/pre-*.sql.gz 2>/dev/null | tail -n +16 | xargs -r rm -f

  # ---- 4. migrations ----------------------------------------------------------------------------------------
  log "4/6 migrations"
  (cd "$REL/backend" && npm run migrate)
fi

# ---- 5. troca atômica ----------------------------------------------------------------------------------------
log "5/6 ligando a release nova"
swap "$REL"
[[ -n "$PREV" && "$PREV" != "$REL" ]] && printf '%s\n' "$PREV" >"$ROOT/previous"
trap - EXIT

if [[ $BOOTSTRAP == 1 ]]; then
  echo "release montada (sem restart): $REL"
  exit 0
fi

# ---- 6. reinício + health check + rollback -------------------------------------------------------------------
log "6/6 reiniciando o serviço"
$SYSTEMCTL restart "$SERVICE"
if health 60 && curl -fsS --max-time 5 -o /dev/null "${HEALTH_URL%/api/health}/"; then
  echo "no ar: $REL"
  summary "✅ Deploy do site/API: \`$(basename "$REL")\`"
else
  echo "::error::o serviço não ficou saudável depois do deploy"
  if [[ -n "$PREV" && -d "$PREV" ]]; then
    echo "voltando para $PREV"
    swap "$PREV"; $SYSTEMCTL restart "$SERVICE"
    health 60 && echo "rollback concluído: $PREV está no ar" || echo "::error::ATENÇÃO: o rollback também não ficou saudável — verifique 'journalctl -u $SERVICE'"
    summary "❌ Deploy falhou; rollback para \`$(basename "$PREV")\`"
  fi
  exit 1
fi

# ---- poda ----------------------------------------------------------------------------------------------------
cur="$(readlink -f "$ROOT/current")"; prev="$(cat "$ROOT/previous" 2>/dev/null || true)"
ls -1dt "$ROOT"/releases/*/ 2>/dev/null | sed 's#/$##' | tail -n +$((KEEP + 1)) | while read -r d; do
  [[ "$d" == "$cur" || "$d" == "$prev" ]] || rm -rf "$d"
done
