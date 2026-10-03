#!/usr/bin/env bash
# Assinatura dos APKs no pipeline.
#   android-signing.sh setup            cria o keystore e os android/key.properties dos dois apps (a partir de segredos)
#   android-signing.sh verify APK       confere que o APK está assinado pela chave de produção (fingerprint esperado)
#   android-signing.sh cleanup          apaga keystore e key.properties
#
# Variáveis (segredos do GitHub): ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD
# Variável pública: ANDROID_CERT_SHA256 (fingerprint SHA-256 do certificado de produção, com ou sem ":")
set -euo pipefail
cd "$(dirname "$0")/../.."

apps=(mobile-client mobile-server)
store="${RUNNER_TEMP:-/tmp}/release.jks"

need() { for v in "$@"; do [[ -n "${!v:-}" ]] || { echo "::error::falta ${v} (segredo/variável do GitHub)"; exit 1; }; done; }

apksigner_bin() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}" f
  f="$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
  [[ -n "$f" ]] || { echo "::error::apksigner não encontrado em $sdk/build-tools"; exit 1; }
  echo "$f"
}

case "${1:-}" in
  setup)
    need ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD
    umask 077
    printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 -d >"$store"
    [[ -s "$store" ]] || { echo "::error::ANDROID_KEYSTORE_BASE64 não decodificou em um keystore"; exit 1; }
    for a in "${apps[@]}"; do
      printf 'storePassword=%s\nkeyPassword=%s\nkeyAlias=%s\nstoreFile=%s\n' \
        "$ANDROID_KEYSTORE_PASSWORD" "$ANDROID_KEY_PASSWORD" "$ANDROID_KEY_ALIAS" "$store" >"$a/android/key.properties"
    done
    echo "keystore e key.properties prontos"
    ;;
  verify)
    apk="${2:?uso: android-signing.sh verify APK}"
    need ANDROID_CERT_SHA256
    want="$(tr -d ':[:space:]' <<<"$ANDROID_CERT_SHA256" | tr 'A-F' 'a-f')"
    [[ "$want" =~ ^[0-9a-f]{64}$ ]] || { echo "::error::ANDROID_CERT_SHA256 inválido (precisa de 64 dígitos hex)"; exit 1; }
    out="$("$(apksigner_bin)" verify --print-certs "$apk")"   # falha (set -e) se a assinatura for inválida
    got="$(grep -E 'Signer #1 certificate SHA-256 digest' <<<"$out" | head -1 | sed 's/.*: *//' | tr -d ':[:space:]' | tr 'A-F' 'a-f')"
    if [[ "$got" != "$want" ]]; then
      echo "::error::o APK NÃO está assinado com a chave de produção (fingerprint $got, esperado $want). Publicar assim impediria os apps instalados de atualizar."
      exit 1
    fi
    echo "assinatura de produção confirmada ($got)"
    ;;
  cleanup)
    rm -f "$store"
    for a in "${apps[@]}"; do rm -f "$a/android/key.properties"; done
    ;;
  *) echo "uso: android-signing.sh setup|verify APK|cleanup" >&2; exit 2 ;;
esac
