#!/usr/bin/env bash
# Teste do deploy.sh / rollback.sh com um projeto de mentira (sem rede, sem Postgres, sem systemd, poucos KB).
#   bash infra/ci/deploy.test.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
T="$(mktemp -d)"; PORT=$((20000 + RANDOM % 20000))
trap 'kill "$(cat "$T/root/fake.pid" 2>/dev/null)" 2>/dev/null || true; if [[ -n "${KEEP_TMP:-}" ]]; then echo "(arquivos de teste mantidos em $T)"; else rm -rf "$T"; fi' EXIT
fails=0
ok()  { echo "  ✔ $*"; }
bad() { echo "  ✖ $*"; fails=$((fails + 1)); }
check() { local d="$1"; shift; if "$@"; then ok "$d"; else bad "$d"; fi; }

# ---- projeto de mentira -------------------------------------------------------------------------------------
SRC="$T/src"; mkdir -p "$SRC"/{backend,frontend,shared}
for d in backend frontend; do
  cat >"$SRC/$d/package.json" <<'JSON'
{"name":"fake","version":"1.0.0","scripts":{
 "build":"node -e \"const fs=require('fs');if(fs.existsSync('BUILD_FAIL'))process.exit(1);fs.mkdirSync('.next/cache',{recursive:true});fs.writeFileSync('.next/BUILD_ID','ok')\"",
 "migrate":"node -e \"const fs=require('fs');if(fs.existsSync('MIGRATE_FAIL'))process.exit(1);fs.appendFileSync(process.env.MIGRATE_LOG,'m\\n')\""}}
JSON
  (cd "$SRC/$d" && npm install --package-lock-only --no-audit --no-fund >/dev/null 2>&1)
done
cat >"$SRC/backend/server.mjs" <<'JS'
import http from 'node:http'; import fs from 'node:fs';
if (fs.existsSync(new URL('./BROKEN', import.meta.url))) process.exit(1); // release "quebrada": não sobe
http.createServer((q, r) => { r.setHeader('content-type', 'application/json'); r.end(q.url === '/api/health' ? '{"ok":true}' : '{"site":true}'); }).listen(Number(process.env.PORT));
JS
echo "export const x = 1" >"$SRC/shared/x.ts"

# ---- servidor de mentira ------------------------------------------------------------------------------------
R="$T/root"; mkdir -p "$R/shared/downloads" "$T/bin"
echo "DATABASE_URL=postgres://u:p@localhost/db" >"$R/shared/backend.env"
cat >"$T/bin/systemctl" <<EOS
#!/usr/bin/env bash
[[ "\$1" == restart ]] || exit 0
[[ -f "$R/fake.pid" ]] && kill "\$(cat "$R/fake.pid")" 2>/dev/null; sleep 0.3
cd "$R/current/backend"
PORT=$PORT nohup node server.mjs >/dev/null 2>&1 &
echo \$! >"$R/fake.pid"
EOS
cat >"$T/bin/pg_dump" <<'EOS'
#!/usr/bin/env bash
[[ -f "${PGFAIL:-/nonexistent}" ]] && exit 1
for i in $(seq 1 80); do echo "-- linha de backup $i $RANDOM$RANDOM$RANDOM"; done
EOS
chmod +x "$T/bin/"*

export RAYKA_ROOT="$R" SYSTEMCTL="$T/bin/systemctl" HEALTH_URL="http://127.0.0.1:$PORT/api/health" MIGRATE_LOG="$T/migrate.log" PATH="$T/bin:$PATH" MIN_FREE_GB=0 GITHUB_SHA=0000000000000000
deploy() { bash "$HERE/deploy.sh" "$SRC" >"$T/out.log" 2>&1 || { tail -15 "$T/out.log" | sed 's/^/      | /' >&2; return 1; }; }
cur() { basename "$(readlink -f "$R/current" 2>/dev/null || echo none)"; }
nreleases() { find "$R/releases" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l; }

echo "1) primeiro deploy (ainda sem current)"
check "sai com sucesso" deploy
A="$(cur)"
check "current aponta para a release nova" test -d "$R/releases/$A/backend/.next"
check "backend/.env é o link para o segredo do servidor" test "$(readlink "$R/releases/$A/backend/.env")" = "$R/shared/backend.env"
check "fez backup do banco" test -n "$(find "$R/backups" -name 'pre-*.sql.gz' 2>/dev/null)"
check "rodou a migration 1 vez" test "$(wc -l <"$T/migrate.log")" -eq 1
check "o servidor responde" curl -fsS "$HEALTH_URL"
check "não sobrou cache de build na release" test ! -e "$R/releases/$A/backend/.next/cache"
check "primeiro deploy não grava 'previous' falso" test ! -s "$R/previous"

echo "2) segundo deploy"
sleep 1; deploy; B="$(cur)"
check "current mudou" test "$A" != "$B"
check "previous = a anterior" test "$(basename "$(cat "$R/previous")")" = "$A"
check "o servidor responde" curl -fsS "$HEALTH_URL"

echo "3) release que não sobe: volta sozinho para a anterior"
touch "$SRC/backend/BROKEN"; sleep 1
check "o deploy falha (código ≠ 0)" bash -c "! bash '$HERE/deploy.sh' '$SRC' >'$T/out.log' 2>&1"
check "current voltou para a release que estava no ar" test "$(cur)" = "$B"
check "o servidor responde de novo" curl -fsS "$HEALTH_URL"
check "avisou do rollback" grep -q "rollback concluído" "$T/out.log"
rm "$SRC/backend/BROKEN"

echo "4) rollback.sh"
check "depois do rollback automático, 'previous' continua sendo a release anterior (A)" test "$(basename "$(cat "$R/previous")")" = "$A"
check "volta para a release anterior" bash -c "bash '$HERE/rollback.sh' >'$T/out.log' 2>&1"
check "current = A" test "$(cur)" = "$A"
check "recusa 'voltar' para o que já está no ar" bash -c "! bash '$HERE/rollback.sh' '$R/releases/$A' >/dev/null 2>&1"
check "recusa pasta fora de releases/" bash -c "! bash '$HERE/rollback.sh' /tmp >/dev/null 2>&1"
bash "$HERE/rollback.sh" "$R/releases/$B" >/dev/null 2>&1 || true

echo "5) falhas ANTES da troca não mexem em nada"
before="$(cur)"; n0="$(nreleases)"
touch "$SRC/backend/BUILD_FAIL"; sleep 1
check "build quebrado: deploy falha" bash -c "! bash '$HERE/deploy.sh' '$SRC' >/dev/null 2>&1"
rm "$SRC/backend/BUILD_FAIL"
check "…current intacto e release pela metade descartada" test "$(cur)" = "$before" -a "$(nreleases)" -eq "$n0"
m0="$(wc -l <"$T/migrate.log")"
touch "$SRC/backend/MIGRATE_FAIL"; sleep 1
check "migration quebrada: deploy falha" bash -c "! bash '$HERE/deploy.sh' '$SRC' >/dev/null 2>&1"
rm "$SRC/backend/MIGRATE_FAIL"
check "…current intacto" test "$(cur)" = "$before"
touch "$T/pgfail"; sleep 1
check "backup falhou: deploy falha ANTES das migrations" bash -c "! PGFAIL='$T/pgfail' bash '$HERE/deploy.sh' '$SRC' >/dev/null 2>&1"
check "…nenhuma migration rodou" test "$(wc -l <"$T/migrate.log")" -eq "$m0"
check "pouco espaço em disco: recusa sem alterar nada" bash -c "! MIN_FREE_GB=99999999 bash '$HERE/deploy.sh' '$SRC' >'$T/out.log' 2>&1 && grep -q 'pouco espaço' '$T/out.log'"
mv "$R/shared/backend.env" "$R/shared/backend.env.off"
check "sem o env do servidor: recusa" bash -c "! bash '$HERE/deploy.sh' '$SRC' >/dev/null 2>&1"
mv "$R/shared/backend.env.off" "$R/shared/backend.env"
check "current ainda é o mesmo" test "$(cur)" = "$before"

echo "6) poda: guarda só KEEP releases, nunca a atual nem a anterior"
for _ in 1 2 3 4; do sleep 1; KEEP_RELEASES=2 deploy; done
check "no máximo 3 releases" test "$(nreleases)" -le 3
check "a atual existe" test -d "$(readlink -f "$R/current")"
check "a anterior existe" test -d "$(cat "$R/previous")"
check "backups podados em no máximo 15" test "$(find "$R/backups" -name 'pre-*.sql.gz' | wc -l)" -le 15

echo
if [[ $fails -eq 0 ]]; then echo "deploy.test: tudo certo"; else echo "deploy.test: $fails falha(s)"; exit 1; fi
