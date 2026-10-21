# Mídias do README

Gera as capturas, os GIFs e os mockups de [`docs/media/`](../../docs/media). Nada aqui toca o backend real: tudo roda contra uma **API de mentira** com dados fictícios (`mock-api.mjs`).

## Pré-requisitos

- Node 22, `ffmpeg`, `cwebp` (libwebp) e Flutter no `PATH`.
- Playwright instalado **fora do repositório** (ele só serve a estes scripts):

  ```bash
  mkdir -p /tmp/pw && cd /tmp/pw && npm init -y && npm i playwright
  export PW_DIR=/tmp/pw
  ```

  Se o Playwright pedir um Chromium que você não tem, aponte para um que já exista com `CHROME=/caminho/do/chrome`.

## Passo a passo

```bash
# 1. API fictícia (:10006)
node scripts/readme-media/mock-api.mjs &

# 2. Site em produção local (:10005), falando com a API fictícia
cd frontend && npm ci
BACKEND_URL=http://127.0.0.1:10006 API_BASE=http://127.0.0.1:10006 npx next build
BACKEND_URL=http://127.0.0.1:10006 API_BASE=http://127.0.0.1:10006 npx next start -p 10005 &

# 3. App Flutter compilado para web e servido em :10007
cd ../mobile-client
flutter build web --release \
  --dart-define=API_BASE=http://127.0.0.1:10006 --dart-define=SITE_BASE=http://127.0.0.1:10006
python3 -m http.server 10007 --bind 127.0.0.1 --directory build/web &
cd ..

# 4. Capturas e quadros (a pasta de trabalho pode ser qualquer uma, fora do repositório)
W=/tmp/readme-work
node scripts/readme-media/capture-web.mjs    $W/web      # telas do site (desktop e celular)
node scripts/readme-media/capture-app.mjs    $W/app      # telas do app (faz o login pela interface)
node scripts/readme-media/capture-hero.mjs   $W/hero     # quadros da animação do logo
node scripts/readme-media/capture-motion.mjs $W/motion   # quadros dos clipes de rolagem e de pedido

# 5. GIFs e MP4 (largura, fps, pausa no último quadro, nome)
node scripts/readme-media/build-clip.mjs $W/hero $W/out 880 24 1.0 hero-loop
node scripts/readme-media/build-clip.mjs $W/motion/web-rolagem $W/out 800 20 0.6 web-rolagem
node scripts/readme-media/build-clip.mjs $W/motion/web-pedido  $W/out 800 20 0.6 web-pedido
GIF_COLORS=64 node scripts/readme-media/build-clip.mjs $W/motion/app-cardapio $W/out 320 12 0.6 app-cardapio
node scripts/readme-media/build-clip.mjs $W/motion/app-pedido  $W/out 360 20 0.8 app-pedido

# 6. Mockups: monte $W/src com as capturas escolhidas (nomes na primeira linha do build-mockups.mjs)
node scripts/readme-media/build-mockups.mjs $W/src $W/mock
node scripts/readme-media/publish-media.mjs $W/mock $W/out docs/media
```

## Por que o loop do hero não tem corte

`capture-hero.mjs` grava a **repetição** da animação (o ponteiro entra no logo): ela parte do logo estático e termina nele. O primeiro e o último quadro saem idênticos, então o GIF fecha o ciclo sem salto. Os quadros vêm do *screencast* do Chrome (PNG com timestamp) e o `build-clip.mjs` respeita a duração real de cada quadro.

## Observações

- As telas do app são o mesmo código Flutter compilado para web, aberto numa janela de 390×844. Os campos de texto do Flutter web só aceitam digitação depois de um toque, por isso `capture-app.mjs` espera um instante antes de digitar.
- `mock-api.mjs` aceita a sessão pelo cookie `mock=1` (site) ou por um token `Bearer mock…` (app).
- Os mockups usam as fotos que já estão em `frontend/public/fotos`, com nomes e preços de item inventados.
