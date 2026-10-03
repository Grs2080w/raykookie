# raykookie / Rayka Estoque

Confeitaria: loja online para clientes e painel da confeiteira (estoque, receitas com preço automático, pedidos, metas e custos).

```
backend/    API (Next.js), banco e regras  ┐ em produção rodam juntos:
frontend/   site, loja e painel (Next.js) ┘ um processo, uma porta (:10005)
shared/     regras e tipos em TypeScript puro (usados pelos dois)
mobile-client/   app Flutter do CLIENTE (cardápio e pedidos)
mobile-server/   app Flutter de GESTÃO (estoque, receitas, pedidos, metas, custos), ícone escuro
mobile-common/   pacote Dart compartilhado pelos dois apps (API, sessão, modelos, preço, widgets)
downloads/       APKs publicados + manifest.json (versões)
infra/      systemd e scripts de build/deploy/verificação
docs/       ARQUITETURA.md e API.md
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

## Pipeline (GitHub Actions)

Testes em todo PR e, na `main`, publicação automática do site/API e dos apps Android (só o que mudou), com assinatura dos APKs, backup do banco,
health check e rollback. Desenho, segredos e passo a passo para ligar: [docs/CI-CD.md](docs/CI-CD.md).

## Subir em produção (systemd)

```bash
bash infra/build.sh            # instala dependências, aplica migrations e gera os builds
sudo bash infra/install.sh     # instala/reinicia o serviço único "rayka" na porta 10005 (aposenta rayka-estoque/-backend/-frontend)
```
Depois de conferir que está tudo no ar, `bash infra/cleanup-legacy.sh` tira da raiz os arquivos do app único antigo (move para `.legacy-AAAAMMDD/`, não apaga).

Em produção o backend serve o build do frontend (`backend/server.mjs`): `/api/*` é a API e o resto é o site, tudo na mesma porta. Variáveis: `backend/.env` (veja o `.env.example`). Em produção use `COOKIE_SECURE=true` (https) no backend.

## Apps Android e versões

Dois apps, cada um com o seu APK: **raykookie** (cliente) e **raykookie gestão** (confeiteira). A versão fica explícita no `version: X.Y.Z+N` do `pubspec.yaml` de cada app (N = número do build, que só pode subir) e aparece:
- dentro dos apps (rodapé da loja; tela "Mais" e login da gestão), com aviso "Nova versão disponível" quando o servidor tem um build maior;
- no site: o APK do cliente na landing page (seção "Baixe o app") e o de gestão em **Custos e ajustes**, com versão, build, tamanho, data e SHA-256.

```bash
bash infra/publish-apps.sh                      # publica o que mudou (o build +N sobe sozinho)
bash infra/publish-apps.sh client --bump=minor  # também sobe o nome da versão (patch|minor|major)
bash infra/publish-apps.sh all --force          # recompila mesmo sem mudanças
```
O script compila em release, assina com a chave `~/.raykookie/release.jks` (**faça backup dela**: sem a mesma chave os celulares não conseguem atualizar por cima), grava o APK em `downloads/` e atualiza `downloads/manifest.json`. O site lê o manifesto na hora: não precisa rebuildar o site para publicar um app novo. O APK de gestão só baixa com a conta da confeiteira logada.

## Loja online

- `/` landing pública; o cardápio vem das **receitas do painel** (só foto, nome e preço por unidade).
- `/cliente/*` login (Google ou e-mail e senha), meus pedidos e novo pedido. Cada pedido vira linhas em **Pedidos** do painel do usuário `SHOP_OWNER` (padrão `rayka`).
- `/login` → `/dashboard`: painel da confeiteira (sem link na landing; só pela URL).
- Login com Google: defina `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` no `backend/.env` (URI de redirecionamento: `{SITE_URL}/api/cliente/google/callback`).

## Backup

O projeto ainda não está em git. Antes de mexer, faça uma cópia (`tar`) ou inicialize o repositório.
