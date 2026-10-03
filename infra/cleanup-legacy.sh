#!/usr/bin/env bash
# Depois de conferir que a nova estrutura está no ar, tira da raiz os arquivos do app único antigo.
# Nada é apagado: tudo vai para .legacy-AAAAMMDD/ (para voltar atrás, mova de volta). Uso: bash infra/cleanup-legacy.sh
set -euo pipefail
cd "$(dirname "$0")/.."
systemctl is-active --quiet rayka || { echo "O serviço rayka não está ativo. Rode antes: sudo bash infra/install.sh"; exit 1; }
DEST=".legacy-$(date +%Y%m%d)"; mkdir -p "$DEST"
for f in app components lib deploy scripts tests migrations public refs .next node_modules next.config.mjs package.json package-lock.json tsconfig.json tsconfig.tsbuildinfo vitest.config.ts postcss.config.mjs global.d.ts next-env.d.ts .env .env.example; do
  [ -e "$f" ] && mv "$f" "$DEST/" && echo "movido: $f"
done
echo "Pronto. O que sobrou na raiz é a nova estrutura. Tudo o que saiu está em $DEST/"
