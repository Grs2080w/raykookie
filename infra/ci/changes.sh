#!/usr/bin/env bash
# Descobre o que mudou entre dois commits e escreve flags (true/false) em $GITHUB_OUTPUT (ou na tela).
#   bash infra/ci/changes.sh <base> <head>
# <base> vazio ou 000…0 (primeiro push / branch nova) = tudo mudou. Dependências entre as pastas:
#   - web  : backend/, frontend/, shared/ e os arquivos de serviço (infra/ci, infra/rayka.service)
#   - client: mobile-client/ + mobile-common/      - server: mobile-server/ + mobile-common/
#   - workflows/scripts do pipeline mudaram => tudo roda (o pipeline é parte do produto)
set -euo pipefail
base="${1:-}"; head="${2:-HEAD}"
out="${GITHUB_OUTPUT:-/dev/stdout}"

if [[ -z "$base" || "$base" =~ ^0+$ ]] || ! git cat-file -e "$base^{commit}" 2>/dev/null; then
  files="__all__"
else
  files="$(git diff --name-only "$base" "$head")"
fi

hit() { # hit <regex>: algum arquivo mudado casa?
  [[ "$files" == "__all__" ]] && return 0
  grep -Eq "$1" <<<"$files"
}
flag() { if "$@"; then echo true; else echo false; fi; }

pipeline=false; hit '^(\.github/|infra/ci/)' && pipeline=true
web=$(flag hit '^(backend|frontend|shared)/|^infra/(rayka\.service|ci/)')
client=$(flag hit '^(mobile-client|mobile-common)/')
server=$(flag hit '^(mobile-server|mobile-common)/')
[[ "$pipeline" == true ]] && { client=true; server=true; web=true; }
docs_only=false
if [[ "$files" != "__all__" && -n "$files" ]] && ! grep -Evq '^(docs/|README|.*\.md$)' <<<"$files"; then docs_only=true; fi

{
  echo "web=$web"
  echo "client=$client"
  echo "server=$server"
  echo "pipeline=$pipeline"
  echo "docs_only=$docs_only"
} >>"$out"
