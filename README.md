# raykookie / Rayka Estoque

Confeitaria: loja online para clientes e painel da confeiteira (estoque, receitas com preço automático, pedidos, metas e custos).

```
backend-java/ API de PRODUÇÃO (Java 25 + Spring Boot, módulos em Clean Architecture): pedidos, pagamentos (Pix e cartão
             pelo Mercado Pago), receitas, cardápio, combos, cupons, devoluções, avisos e downloads. Container Docker.
backend/    API antiga (Next.js/Node) — desligada desde 10/10/2026 (container parado "raykookie-api-node"); o código
             fica como referência. Testes de fumaça em backend/tests/e2e/ (npm run test:e2e).
frontend/   site, loja e painel (Next.js) — em produção vai pra Vercel
shared/     regras e tipos em TypeScript puro usados pelo frontend (o backend tem a própria cópia em backend/shared/)
mobile-client/   app Flutter do CLIENTE (cardápio e pedidos)
mobile-server/   app Flutter raykookie business (estoque, receitas, pedidos, metas, custos), ícone escuro
mobile-common/   pacote Dart compartilhado pelos dois apps (API, sessão, modelos, preço, widgets)
downloads/       APKs publicados + manifest.json (versões) — o container da API lê daqui
infra/      scripts de build/verificação locais + infra/ci/ (pipeline)
docs/       ARQUITETURA.md e API.md
raykookie-arts/  logos e referências visuais (não entram no build)
legacy/     app único antigo, de antes da divisão backend/frontend — não roda (veja legacy/README.md)
```

Veja [docs/ARQUITETURA.md](docs/ARQUITETURA.md) e [docs/API.md](docs/API.md). O plano da reconstrução em Java está em
`docs/superpowers/plans/2026-10-07-backend-java-reconstrucao.md`.

## Rodar em desenvolvimento

```bash
# 1) API Java (Postgres próprio de desenvolvimento em 127.0.0.1:54331; as migrations rodam sozinhas no boot)
cd backend-java && docker compose -f docker-compose.dev.yml up -d
./mvnw -q install -DskipTests -DskipITs -Djacoco.skip=true   # uma vez (e depois de mudar outros módulos)
PORT=10006 ./mvnw -pl app spring-boot:run -Dspring-boot.run.profiles=local   # API de desenvolvimento em :10006

# 2) frontend (outro terminal)
cd frontend && npm install && npm run dev                        # site em :10005 (repassa /api a :10006; veja frontend/.env.development)

# 3) apps (outro terminal): escolha mobile-client ou mobile-server
cd mobile-client && flutter pub get && flutter run --dart-define=API_BASE=http://10.0.2.2:10005   # emulador Android; no navegador use http://localhost:10005
```

## Verificar tudo (testes, tipos e builds)

```bash
cd backend-java && ./mvnw verify   # API Java: testes (Postgres real via Testcontainers), Checkstyle, arquitetura e cobertura
bash infra/check.sh                # backend Node + frontend + mobile
bash infra/check.sh --fast         # só tipos e testes, sem build
```

## Produção: front na Vercel, API em Docker

Diferente do dev (onde os dois rodam juntos via proxy do Vite/Next), em produção o front e a API são
dois deploys independentes — mesmo padrão usado pelos outros apps deste workspace (finance, lineup, wraap, agest).

- **Front** (`raykookie.com.br`): projeto Vercel `raykookie-frontend`, `rootDirectory=frontend`,
  deploy automático a cada push na `main` (integração Git da própria Vercel). Variáveis do projeto na Vercel:
  `BACKEND_URL` (fetches do lado servidor) e `SITE_URL` (og:image,
  redirects) — **não precisam existir**: os endereços vêm de `config/urls.json` (ver `docs/urls.md`). O `rewrites` do
  `frontend/next.config.mjs` repassa `/api/*` pra API, então do ponto de vista do navegador
  tudo é same-origin — sem CORS, cookie de sessão funciona igual ao monólito antigo.

- **API** (`api.raykookie.com.br`): a API **Java**, num container Docker **sempre ligado** (não dorme — Pix,
  webhooks do Mercado Pago e as tarefas de 5 em 5 minutos precisam do processo de pé). O `reverse-host` (`rproxy`) da
  máquina liga o domínio ao **container chamado `raykookie-api` na porta 20010** (`services.toml`, `always_on = true`).

  ```bash
  docker build -t raykookie-api-java:candidate backend-java
  docker tag raykookie-api-java:latest raykookie-api-java:previous     # guarda a atual para voltar atrás
  docker tag raykookie-api-java:candidate raykookie-api-java:latest
  bash infra/ci/run-api.sh raykookie-api-java:latest                   # recria o container raykookie-api
  # voltar atrás: bash infra/ci/run-api.sh raykookie-api-java:previous
  ```

  `infra/ci/run-api.sh` é o mesmo que o CD usa: rede do host, porta 20010, perfil `prod`, todas as tarefas ligadas,
  `downloads/` montado em `/app/downloads` e `~/.raykookie` só leitura. Variáveis: `backend/.env.docker` (banco e
  Mercado Pago, o mesmo arquivo da época do Node) mais `~/.raykookie/java.env` (só do Java: `DOWNLOAD_SECRET`,
  `MP_PUBLIC_KEY`, `PAYMENTS_CARD_ENABLED`, `JOBS_ENABLED=true`…). Nenhum dos dois vai para o Git.

  As migrations do banco (`backend-java/platform/persistence/src/main/resources/db/migration`) rodam no boot pelo
  Flyway; são sempre aditivas. O container antigo do Node (`raykookie-api-node`) fica parado; não o religue no mesmo
  banco sem desligar as tarefas de um dos dois.

## CI/CD

`.github/workflows/ci.yml` testa tudo (API Java com `mvnw verify`, backend Node com Postgres efêmero, build do
front, os três pacotes Flutter, scripts e workflows com shellcheck/actionlint) em todo push e PR. A API Java faz
parte do `ci-ok`: se ela falhar, nada é implantado.

`.github/workflows/cd.yml`, em todo push na `main`: roda o CI inteiro e, se passar, compila e **assina**
os APKs que mudaram (cliente e/ou business) com a chave de produção, confere a assinatura contra o
fingerprint esperado, registra em `downloads/` — isso roda num runner **self-hosted** (`raykookie-prod`,
label `rayka-prod`) porque é nesta própria máquina que o container da API lê essa pasta — e cria uma
[Release no GitHub](../../releases) por app publicado, com o APK e o sha256 em anexo.

Segredos/variáveis do repositório que o CD precisa (Settings → Secrets and variables → Actions):
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
(a partir de `~/.raykookie/release.jks` e `key.properties` — **nunca gere um keystore novo**: sem ser
exatamente a mesma chave, os celulares com o app instalado param de conseguir atualizar), a variável
`ANDROID_CERT_SHA256` (fingerprint público, não é segredo) e, opcionalmente,
`GOOGLE_SERVICES_JSON_SERVER_BASE64` (push notifications do app raykookie business).

Quando `backend-java/` muda, o CD também **implanta a API**: builda a imagem, troca o container `raykookie-api` com
`infra/ci/run-api.sh` e, se a saúde não voltar em 2 minutos, volta sozinho para a imagem anterior.

O front não passa por este pipeline — a Vercel implanta direto do GitHub.

## Apps Android e versões

Dois apps, cada um com o seu APK: **raykookie** (cliente) e **raykookie business** (confeiteira). A versão fica explícita no `version: X.Y.Z+N` do `pubspec.yaml` de cada app (N = número do build, que só pode subir) e aparece:
- dentro dos apps (rodapé da loja; tela "Mais" e login do business), com a faixa "Nova versão disponível" quando o servidor tem um build maior: **Atualizar** baixa o APK no próprio app (barra de progresso, confere o SHA-256) e abre o instalador do Android;
- por push: quando o CD publica um build novo, a API avisa todos os aparelhos daquele app ("Nova versão do app"), uma vez por build;
- no site: o APK do cliente na landing page (seção "Baixe o app") e o do business em **Custos e ajustes**, com versão, build, tamanho, data e SHA-256.

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

- `/` landing pública; o cardápio vem da **página Cardápio** do app raykookie business (Mais → Cardápio): os produtos
  ligados, com foto, preço, selos de alergia e (se escolhido) a lista de ingredientes, e os **combos**. O mesmo
  cardápio (`/api/menu`) aparece no app do cliente e no link de compartilhar.
- Receitas: cada produto pode ter **várias receitas** (abas Receita 1, Receita 2…); a **padrão** dá o preço do
  cardápio, dos combos, o lucro e as metas.
- Combos: N produtos com quantidade; preço sugerido = soma dos avulsos menos o desconto de combo (padrão 10%), nunca
  abaixo do custo + margem mínima — e pode ser trocado. No pedido, o combo vira uma linha só.
- Cupons (Mais → Cupons): código, desconto de 1% a 90% e valor mínimo opcional ("a partir de" ou "acima de"). O
  cliente digita no último passo do pedido; o servidor confere com o total do cardápio e o total cobrado já vem com
  o desconto.
- `/cliente/*` login (Google ou e-mail e senha), meus pedidos e novo pedido. Cada pedido vira linhas em **Pedidos** do painel do usuário `SHOP_OWNER` (padrão `rayka`).
- `/login` → `/dashboard`: painel da confeiteira (sem link na landing; só pela URL).
- Login com Google: defina `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` no `backend/.env` (URI de redirecionamento: `{SITE_URL}/api/cliente/google/callback`).

## Licença

Código **proprietário**, não é open source: o repositório é público só para rodar o CI. Todos os direitos reservados,
incluindo o nome **Raykookie**, a logo e a identidade visual. Copiar, usar, modificar ou redistribuir exige autorização
por escrito; ver [LICENSE](LICENSE).
