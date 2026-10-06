#!/usr/bin/env bash
# Prepara a MÁQUINA DE PRODUÇÃO para o deploy automático (uma vez). Rode com sudo, da raiz do repositório:
#   sudo bash infra/ci/setup-server.sh
#
# O que faz:
#   1. cria /srv/rayka/{releases,shared,backups} (dono: o seu usuário)
#   2. COPIA o backend/.env atual para /srv/rayka/shared/backend.env e os APKs/manifesto de downloads/ para shared/downloads/
#      (os originais ficam onde estão; nada é apagado)
#   3. monta a primeira release a partir deste código (build, SEM migration e SEM restart) e liga o "current"
#   4. instala o serviço novo (aponta para /srv/rayka/current) e dá permissão sudo SÓ para reiniciar o serviço "rayka"
#   5. reinicia o serviço e confere se subiu — se não subir, restaura o serviço antigo
# O serviço antigo (infra/rayka.service, que roda de dentro do repositório) é guardado em /etc/systemd/system/rayka.service.bak.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "rode com sudo: sudo bash infra/ci/setup-server.sh"; exit 1; }
USER_NAME="${SUDO_USER:-}"; [[ -n "$USER_NAME" && "$USER_NAME" != root ]] || { echo "rode via sudo a partir do seu usuário (não como root direto)"; exit 1; }
ROOT="${RAYKA_ROOT:-/srv/rayka}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
UNIT=/etc/systemd/system/rayka.service
SYSTEMCTL_BIN="$(command -v systemctl)"

NODE_BIN="${NODE_BIN:-}"
[[ -n "$NODE_BIN" ]] || NODE_BIN="$(grep -E '^ExecStart=' "$UNIT" 2>/dev/null | head -1 | sed -e 's/^ExecStart=//' -e 's/ .*//')"
[[ -x "$NODE_BIN" ]] || { echo "não achei o node do serviço atual. Informe: sudo NODE_BIN=/caminho/para/node bash infra/ci/setup-server.sh"; exit 1; }
[[ -f "$REPO/backend/.env" ]] || { echo "falta $REPO/backend/.env"; exit 1; }

echo "==> 1/5 pastas em $ROOT"
install -d -o "$USER_NAME" -g "$USER_NAME" -m 755 "$ROOT" "$ROOT/releases" "$ROOT/backups"
install -d -o "$USER_NAME" -g "$USER_NAME" -m 750 "$ROOT/shared"

echo "==> 2/5 segredos e downloads"
[[ -f "$ROOT/shared/backend.env" ]] || install -o "$USER_NAME" -g "$USER_NAME" -m 600 "$REPO/backend/.env" "$ROOT/shared/backend.env"
install -d -o "$USER_NAME" -g "$USER_NAME" -m 755 "$ROOT/shared/downloads"
if [[ -d "$REPO/downloads" ]]; then
  cp -an "$REPO/downloads/." "$ROOT/shared/downloads/"
  chown -R "$USER_NAME:$USER_NAME" "$ROOT/shared/downloads"
fi

echo "==> 3/5 primeira release (build, sem migration e sem restart)"
sudo -u "$USER_NAME" env "PATH=$(dirname "$NODE_BIN"):$PATH" RAYKA_ROOT="$ROOT" bash "$REPO/infra/ci/deploy.sh" --bootstrap "$REPO"

echo "==> 4/5 serviço e sudo restrito"
[[ -f "$UNIT.bak" ]] || { [[ -f "$UNIT" ]] && cp -p "$UNIT" "$UNIT.bak" || true; }
sed -e "s#@USER@#$USER_NAME#g" -e "s#@ROOT@#$ROOT#g" -e "s#@NODE@#$NODE_BIN#g" "$REPO/infra/ci/rayka.service" >"$UNIT"
chmod 644 "$UNIT"
SUDOERS=/etc/sudoers.d/rayka-deploy
printf '%s ALL=(root) NOPASSWD: %s restart rayka, %s is-active rayka\n' "$USER_NAME" "$SYSTEMCTL_BIN" "$SYSTEMCTL_BIN" >"$SUDOERS.tmp"
chmod 440 "$SUDOERS.tmp"
visudo -cf "$SUDOERS.tmp" >/dev/null || { rm -f "$SUDOERS.tmp"; echo "sudoers inválido; nada foi instalado"; exit 1; }
mv "$SUDOERS.tmp" "$SUDOERS"
"$SYSTEMCTL_BIN" daemon-reload

echo "==> 5/5 reiniciando"
"$SYSTEMCTL_BIN" restart rayka
ok=0; for _ in $(seq 1 60); do curl -fsS http://127.0.0.1:10005/api/health >/dev/null 2>&1 && { ok=1; break; }; sleep 1; done
if [[ $ok == 1 ]]; then
  echo "pronto: o site/API agora roda de $ROOT/current. Próximos passos: docs/CI-CD.md (runner e segredos do GitHub)."
else
  echo "O serviço novo NÃO subiu; restaurando o antigo."
  if [[ -f "$UNIT.bak" ]]; then cp -p "$UNIT.bak" "$UNIT"; "$SYSTEMCTL_BIN" daemon-reload; "$SYSTEMCTL_BIN" restart rayka; fi
  exit 1
fi
