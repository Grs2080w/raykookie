#!/usr/bin/env bash
# Teste do changes.sh: monta um repositório temporário e confere o que cada tipo de mudança aciona.
#   bash infra/ci/changes.test.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fails=0

cd "$T"
git init -q -b main .
git config user.email t@t; git config user.name t
mkdir -p backend frontend shared mobile-client mobile-server mobile-common docs infra/ci .github/workflows
for f in backend/a frontend/a shared/a mobile-client/a mobile-server/a mobile-common/a docs/a.md README.md infra/rayka.service infra/ci/x.sh .github/workflows/ci.yml; do echo 0 >"$f"; done
git add -A; git commit -qm base

# expect <descrição> <arquivo a mudar> web client server pipeline docs_only
expect() {
  local desc="$1" file="$2"; shift 2
  local before; before="$(git rev-parse HEAD)"
  echo "$RANDOM" >>"$file"; git add -A; git commit -qm "$desc"
  local out; out="$(GITHUB_OUTPUT=/dev/stdout bash "$HERE/changes.sh" "$before" HEAD | tr '\n' ' ')"
  local want="web=$1 client=$2 server=$3 pipeline=$4 docs_only=$5 "
  if [[ "$out" == "$want" ]]; then echo "  ✔ $desc"; else echo "  ✖ $desc"; echo "      esperado: $want"; echo "      obtido  : $out"; fails=$((fails + 1)); fi
}
expect "backend → só o site/API"                     backend/a            true  false false false false
expect "frontend → só o site/API"                    frontend/a           true  false false false false
expect "shared → só o site/API"                      shared/a             true  false false false false
expect "mobile-client → só o app do cliente"         mobile-client/a      false true  false false false
expect "mobile-server → só o app de gestão"          mobile-server/a      false false true  false false
expect "mobile-common → os dois apps"                mobile-common/a      false true  true  false false
expect "só documentação → nada"                      docs/a.md            false false false false true
expect "README → nada"                               README.md            false false false false true
expect "serviço systemd → site/API"                  infra/rayka.service  true  false false false false
expect "script do pipeline → TUDO (o pipeline é produto)" infra/ci/x.sh   true  true  true  true  false
expect "workflow → TUDO"                             .github/workflows/ci.yml true true true true false

# base vazia / 000…0 / inexistente (primeiro push, branch nova) = tudo mudou
for base in "" 0000000000000000000000000000000000000000 deadbeefdeadbeefdeadbeefdeadbeefdeadbeef; do
  out="$(GITHUB_OUTPUT=/dev/stdout bash "$HERE/changes.sh" "$base" HEAD | tr '\n' ' ')"
  if [[ "$out" == "web=true client=true server=true pipeline=true docs_only=false " ]]; then echo "  ✔ base '${base:0:8}' → tudo"; else echo "  ✖ base '${base:0:8}': $out"; fails=$((fails + 1)); fi
done

if [[ $fails -eq 0 ]]; then echo "changes.test: tudo certo"; else echo "changes.test: $fails falha(s)"; exit 1; fi
