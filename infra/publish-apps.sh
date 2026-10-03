#!/usr/bin/env bash
# Publica os apps Android (cliente e gestão) em downloads/ para o site oferecer o download.
# Só reconstrói o que mudou; o build (+N) sobe sozinho. Exemplos:
#   bash infra/publish-apps.sh                 # os dois, só o que mudou
#   bash infra/publish-apps.sh client --bump=minor
#   bash infra/publish-apps.sh all --force
cd "$(dirname "$0")/.."
exec node infra/publish-apps.mjs "$@"
