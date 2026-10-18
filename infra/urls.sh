#!/usr/bin/env bash
# Lê uma URL de config/urls.json (fonte única dos endereços públicos), sem depender de jq.
#   bash infra/urls.sh SITE_URL        →  https://raykookie.com.br
#   source infra/urls.sh; url_of API_BASE
set -euo pipefail

# ── 1. Leitura
url_of() {
  local file value
  file="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/config/urls.json"
  value="$(sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$file")"
  [[ -n "$value" ]] || { echo "Chave $1 não encontrada em $file" >&2; return 1; }
  printf '%s\n' "$value"
}

# ── 2. Uso direto
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then url_of "${1:?chave: SITE_URL | GESTAO_HOST | API_BASE}"; fi
