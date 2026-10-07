# raykookie / Rayka Estoque

Confeitaria: loja online para clientes e painel da confeiteira (estoque, receitas com preço automático, pedidos, metas e custos).

```
backend/    API (Next.js), banco e regras — em produção roda sozinha, num container Docker
frontend/   site, loja e painel (Next.js) — em produção vai pra Vercel
shared/     regras e tipos em TypeScript puro (usados pelos dois)
mobile-client/   app Flutter do CLIENTE (cardápio e pedidos)
mobile-server/   app Flutter de GESTÃO (estoque, receitas, pedidos, metas, custos), ícone escuro
mobile-common/   pacote Dart compartilhado pelos dois apps (API, sessão, modelos, preço, widgets)
downloads/       APKs publicados + manifest.json (versões) — o container da API lê daqui
infra/      scripts de build/verificação locais + infra/ci/ (pipeline)
tests/server/    testes de fumaça do backend/server.mjs (node --test tests/server/server.test.mjs, depois dos builds)
docs/       ARQUITETURA.md e API.md
raykookie-arts/  logos e referências visuais (não entram no build)
legacy/     app único antigo, de antes da divisão backend/frontend — não roda (veja legacy/README.md)
```

Veja [docs/ARQUITETURA.md](docs/ARQUITETURA.md) e [docs/API.md](docs/API.md).

## Rodar em desenvolvimento

```bash
# 1) banco (uma vez): cria role/banco e o backend/.env
sudo bash backend/scripts/setup-db.sh      # ou copie backend/.env.example para backend/.env e ajuste

# 2) backend
cd backend && npm install && npm run migrate && npm run dev      # API de desenvolvimento em :10006

# 3) frontend (outro terminal)
cd frontend && npm install && npm run dev                        # site em :10005 (repassa /api a :10006; veja frontend/.env.development)

# 4) apps (outro terminal): escolha mobile-client ou mobile-server
cd mobile-client && flutter pub get && flutter run --dart-define=API_BASE=http://10.0.2.2:10005   # emulador Android; no navegador use http://localhost:10005
```

## Verificar tudo (testes, tipos e builds)

```bash
bash infra/check.sh            # backend + frontend + mobile
bash infra/check.sh --fast     # só tipos e testes, sem build
```

## Produção: front na Vercel, API em Docker

Diferente do dev (onde os dois rodam juntos via proxy do Vite/Next), em produção o front e a API são
dois deploys independentes — mesmo padrão usado pelos outros apps deste workspace (finance, lineup, wraap, agest).

- **Front** (`raykookie.kntap.com.br`): projeto Vercel `raykookie-frontend`, `rootDirectory=frontend`,
  deploy automático a cada push na `main` (integração Git da própria Vercel). Variáveis do projeto na Vercel:
  `BACKEND_URL=https://raykookie-api.kntap.com.br` (fetches do lado servidor) e `SITE_URL` (og:image,
  redirects). `frontend/vercel.json` reescreve `/api/*` pra API, então do ponto de vista do navegador
  tudo é same-origin — sem CORS, cookie de sessão funciona igual ao monólito antigo.

- **API** (`raykookie-api.kntap.com.br`): container Docker, **sempre ligado** (não dorme — Pix, webhooks
  do Mercado Pago e o tick de tarefas periódicas a cada 5min precisam do processo de pé o tempo todo).
  Roteado pelo `reverse-host` (`rproxy`) da máquina com `always_on = true` no `services.toml`.

  ```bash
  # build (contexto = raiz do repo, por causa de ../shared)
  docker build -f backend/Dockerfile -t raykookie-api .

  # .env sem aspas nos valores (--env-file do Docker não as remove como o systemd fazia)
  sed -E 's/^([A-Z_]+)="(.*)"$/\1=\2/' backend/.env > backend/.env.docker

  docker rm -f raykookie-api 2>/dev/null
  docker run -d --name raykookie-api --network host --restart unless-stopped \
    --env-file backend/.env.docker \
    -v ~/.raykookie:/home/g_snts/.raykookie:ro \
    -v "$(pwd)/downloads:/app/downloads" \
    raykookie-api
  ```

  `backend/Dockerfile` builda só `backend/` + `shared/` (sem `frontend/`) — `backend/server.mjs` detecta
  a ausência do build do front e serve só a API. O volume de `downloads/` é o que deixa o site e os apps
  saberem a versão publicada mais recente (veja a seção de apps abaixo); o de `~/.raykookie` dá acesso
  ao `.json` da conta de serviço do Firebase (push de novos pedidos pro app de gestão).

  Pra atualizar a API depois de mudar `backend/`: rebuilda a imagem e recria o container (`docker rm -f` +
  `docker run` de novo — um `docker restart` sozinho não pega um `.env` novo nem uma imagem nova).

## CI/CD

`.github/workflows/ci.yml` testa tudo (backend com Postgres efêmero, build do front, os três pacotes
Flutter, scripts e workflows com shellcheck/actionlint) em todo push e PR.

`.github/workflows/cd.yml`, em todo push na `main`: roda o CI inteiro e, se passar, compila e **assina**
os APKs que mudaram (cliente e/ou gestão) com a chave de produção, confere a assinatura contra o
fingerprint esperado, registra em `downloads/` — isso roda num runner **self-hosted** (`raykookie-prod`,
label `rayka-prod`) porque é nesta própria máquina que o container da API lê essa pasta — e cria uma
[Release no GitHub](../../releases) por app publicado, com o APK e o sha256 em anexo.

Segredos/variáveis do repositório que o CD precisa (Settings → Secrets and variables → Actions):
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
(a partir de `~/.raykookie/release.jks` e `key.properties` — **nunca gere um keystore novo**: sem ser
exatamente a mesma chave, os celulares com o app instalado param de conseguir atualizar), a variável
`ANDROID_CERT_SHA256` (fingerprint público, não é segredo) e, opcionalmente,
`GOOGLE_SERVICES_JSON_SERVER_BASE64` (push notifications do app de gestão).

O front não passa por este pipeline — a Vercel implanta direto do GitHub. A API também não: o container
muda com pouca frequência e o deploy é manual (seção acima).

## Apps Android e versões

Dois apps, cada um com o seu APK: **raykookie** (cliente) e **raykookie gestão** (confeiteira). A versão fica explícita no `version: X.Y.Z+N` do `pubspec.yaml` de cada app (N = número do build, que só pode subir) e aparece:
- dentro dos apps (rodapé da loja; tela "Mais" e login da gestão), com aviso "Nova versão disponível" quando o servidor tem um build maior;
- no site: o APK do cliente na landing page (seção "Baixe o app") e o de gestão em **Custos e ajustes**, com versão, build, tamanho, data e SHA-256.

O jeito normal de publicar é só dar push na `main` — o CD acima compila, assina e publica sozinho (veja
a seção de CI/CD). `infra/publish-apps.sh` continua existindo como alternativa pra compilar e publicar
**desta máquina**, sem passar pelo GitHub:

```bash
bash infra/publish-apps.sh                      # publica o que mudou (o build +N sobe sozinho)
bash infra/publish-apps.sh client --bump=minor  # também sobe o nome da versão (patch|minor|major)
bash infra/publish-apps.sh all --force          # recompila mesmo sem mudanças
```
O script compila em release, assina com a chave `~/.raykookie/release.jks` (**faça backup dela**: sem a mesma chave os celulares não conseguem atualizar por cima), grava o APK em `downloads/` e atualiza `downloads/manifest.json`. O APK de gestão só baixa com a conta da confeiteira logada.

## Loja online

- `/` landing pública; o cardápio vem das **receitas do painel** (só foto, nome e preço por unidade).
- `/cliente/*` login (Google ou e-mail e senha), meus pedidos e novo pedido. Cada pedido vira linhas em **Pedidos** do painel do usuário `SHOP_OWNER` (padrão `rayka`).
- `/login` → `/dashboard`: painel da confeiteira (sem link na landing; só pela URL).
- Login com Google: defina `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` no `backend/.env` (URI de redirecionamento: `{SITE_URL}/api/cliente/google/callback`).
