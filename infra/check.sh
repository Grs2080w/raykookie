#!/usr/bin/env bash
# Roda as verificações de todos os projetos (mesmos passos que o pipeline vai executar).
# Uso: bash infra/check.sh [--fast]     (--fast: sem builds)
set -euo pipefail
cd "$(dirname "$0")/.."
FAST=0; [ "${1:-}" = "--fast" ] && FAST=1
export PATH="$HOME/development/flutter/bin:$PATH"
step() { printf '\n==> %s\n' "$*"; }

step "backend: tipos e testes"
( cd backend && npx tsc --noEmit && npm test )
step "frontend: tipos"
( cd frontend && npx tsc --noEmit )
if command -v flutter >/dev/null 2>&1; then
  for pkg in mobile-common mobile-client mobile-server; do
    step "$pkg: análise e testes"
    ( cd "$pkg" && flutter pub get >/dev/null && flutter analyze && flutter test )
  done
else
  echo "(flutter não encontrado: pulei o mobile)"
fi
if [ "$FAST" = 0 ]; then
  step "backend: build";  ( cd backend && npm run build )
  step "frontend: build"; ( cd frontend && npm run build )
  if command -v flutter >/dev/null 2>&1; then
    for pkg in mobile-client mobile-server; do step "$pkg: build web (verifica que compila)"; ( cd "$pkg" && flutter build web --release ); done
  fi
fi
echo; echo "Tudo certo."
