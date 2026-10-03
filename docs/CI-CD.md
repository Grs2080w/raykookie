# Pipeline (GitHub Actions): testes, build e publicação automática

Tudo que roda no GitHub está em `.github/workflows/`; os scripts que ele chama estão em `infra/ci/` e são testáveis na sua máquina
(inclusive o deploy e o rollback, com um projeto de mentira). Este guia tem o desenho, o passo a passo para ligar e a operação do dia a dia.

## Visão geral

```
 pull request / push em outra branch                push na main (ou "Run workflow")
 ───────────────────────────────────                 ─────────────────────────────────────────
 CI  (nuvem, GitHub-hosted)                          CI  (o mesmo, via workflow_call)
   lint   shellcheck + actionlint + testes dos scripts   │
   backend  tsc + migrations + testes (Postgres 17)      ▼  só se o CI passou
   web      tsc do frontend + build do site e da API   plan   decide o que mudou (web / cliente / gestão)
   flutter  analyze + test (common, cliente, gestão)     │
   android-check  build debug do app que mudou           ├─ build-apps (nuvem, ambiente "production")
   ci-ok    ← único check obrigatório da main            │     APK release ASSINADO + confere o fingerprint de produção
                                                         ▼
                                                      deploy (SEU servidor, runner self-hosted)
                                                         site/API: release nova → backup → migrations → troca → health → rollback
                                                         APKs: registra em downloads/ (o site e os apps leem o manifesto)
                                                         ▼
                                                      release (nuvem): Release no GitHub com os APKs (histórico/backup)
```

- **Nuvem (GitHub-hosted, Ubuntu):** testes, builds e a assinatura dos APKs. Nada aqui enxerga o seu servidor.
- **Seu servidor (runner self-hosted, etiqueta `rayka-prod`):** só o job `deploy` (e o `rollback`). Ele chama o GitHub (saída
  HTTPS); você não abre porta nenhuma. Os segredos do servidor (`backend/.env`: banco, Mercado Pago, Google…) **nunca vão para o GitHub**.
- **Só publica o que mudou** (`infra/ci/changes.sh`): mexeu em `backend/`, `frontend/` ou `shared/` → site/API; em `mobile-client/` → app do
  cliente; em `mobile-server/` → app de gestão; em `mobile-common/` → os dois apps. Só documentação (`docs/`, `*.md`) não publica nada.
  Mexeu em `.github/` ou `infra/ci/` → o pipeline é parte do produto: publica tudo.
- **Uma publicação por vez, nunca cancelada no meio** (`concurrency: cd`); a seguinte entra na fila.

## O que cada trava protege

| Risco | Proteção |
|---|---|
| Testes de integração "passando" porque foram pulados (aconteceu aqui com o shell fish) | o CI falha se o resultado tiver `skipped`/`todo` |
| Publicar APK assinado com a chave errada ou de debug (apps instalados parariam de atualizar) | `android-signing.sh verify` compara o certificado com `ANDROID_CERT_SHA256`; sem a variável, falha |
| Build que não aumenta (o Android não atualiza para trás) | `versionCode = offset + nº da execução`; `publish-prebuilt.mjs` recusa build ≤ ao publicado |
| APK corrompido/trocado entre a nuvem e o servidor | sha256 conferido na publicação |
| Migration que quebra o banco | backup `pg_dump` antes (últimos 15 ficam em `/srv/rayka/backups`); o backup e as migrations só rodam depois do build ter dado certo |
| Release ruim no ar | health check (API + site) por até 60 s; se falhar, volta sozinho para a release anterior |
| Disco cheio derrubando o servidor | o deploy recusa começar com menos de 4 GB livres e apaga releases antigas antes de montar a nova |
| Dependências que mudam sozinhas | `npm ci` e `flutter pub get --enforce-lockfile`: só o que está nos lockfiles |
| Segredos de assinatura vazando para código de PR | ficam no ambiente `production`, restrito à branch `main`; PRs de forks não recebem segredos |
| Deploys simultâneos | `concurrency` sem cancelamento |

Os scripts têm testes próprios, que o CI roda (job `lint`): `deploy.test.sh` (33 verificações: primeiro deploy, rollback automático, `rollback.sh`,
falhas de build/migration/backup/disco/env antes da troca, poda), `changes.test.sh` (o que cada mudança aciona) e `publish-prebuilt.test.mjs`
(manifesto, downgrade, sha256, poda).

## ⚠️ Antes de ligar: espaço em disco

Esta máquina estava com o disco em **99%** (≈2 GB livres). Cada release do site/API ocupa ~0,8 GB e o deploy exige 4 GB livres, então **libere espaço antes**
(nenhum desses é apagado pelo pipeline; são suas decisões):

| O quê | Tamanho aprox. | Como |
|---|---|---|
| `mobile-client/build` e `mobile-server/build` | 4 GB cada | `cd mobile-client && flutter clean` (idem `mobile-server`); o build volta a ser gerado quando precisar |
| `~/.gradle` | 10 GB | `rm -rf ~/.gradle/caches` (refaz sozinho no próximo build local) |
| `~/.npm` | 7 GB | `npm cache clean --force` |

Com o pipeline, você deixa de compilar APK nesta máquina, então os dois `build/` e o Gradle deixam de ser necessários no dia a dia.

## Ligando (uma vez)

O repositório já existe em `github.com/Grs2080w/raykookie` (mantenha **privado**). Este pipeline entra por uma branch/PR: o próprio PR já roda o CI na nuvem.

### 1. Ambiente `production` (Settings → Environments → New environment)
- **Deployment branches:** *Selected branches* → só `main`. Isso impede que qualquer outra branch leia os segredos.
- (Opcional) **Required reviewers:** você mesmo, se quiser aprovar cada publicação manualmente.

Segredos do ambiente (Environment secrets):

| Segredo | Valor |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 ~/.raykookie/release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | senha do keystore (`storePassword` do `key.properties`) |
| `ANDROID_KEY_ALIAS` | alias da chave (`keyAlias`) |
| `ANDROID_KEY_PASSWORD` | senha da chave (`keyPassword`) |
| `GOOGLE_SERVICES_JSON_CLIENT_BASE64` | *(opcional)* `base64 -w0 google-services.json` do app do cliente (quando registrar `br.com.kntap.raykookie` no Firebase) |
| `GOOGLE_SERVICES_JSON_SERVER_BASE64` | *(opcional)* idem para o app de gestão; o arquivo atual já está versionado (config pública do Firebase), então só use se preferir tirá-lo do repositório |

Variáveis do ambiente (Environment variables):

| Variável | Valor |
|---|---|
| `ANDROID_CERT_SHA256` | **obrigatória.** Fingerprint SHA-256 da chave de produção. Os APKs publicados hoje têm `1a6fef81e59210e3b424b91eb5998890338b3a06d9257d835443746db5787309`; confira com `keytool -list -v -keystore ~/.raykookie/release.jks` |
| `API_BASE` | *(opcional)* padrão `https://raykookie.kntap.com.br` |
| `GOOGLE_SERVER_CLIENT_ID` | *(opcional)* ID do cliente OAuth "Web" do Google (público); o mesmo `GOOGLE_CLIENT_ID` do `backend/.env` |
| `ANDROID_BUILD_OFFSET` | *(opcional)* padrão 100. Hoje os builds publicados são 17 (cliente) e 15 (gestão): 100 + execução 1 já é maior. Só aumente se o contador de execuções do workflow voltar a zero |

> **Guarde um backup do keystore fora do GitHub** (gerenciador de senhas / pen drive). Quem perde a chave não consegue mais atualizar os apps instalados.

### 2. Preparar o servidor (uma vez, com sudo)
```bash
sudo bash infra/ci/setup-server.sh
```
Cria `/srv/rayka/{releases,shared,backups}`, copia o `backend/.env` para `/srv/rayka/shared/backend.env` e os APKs de `downloads/`
para `/srv/rayka/shared/downloads`, monta a primeira release (sem migration e sem restart), instala o serviço novo (que roda de
`/srv/rayka/current`) e libera `sudo` **somente** para `systemctl restart rayka` / `is-active rayka`. O serviço antigo fica guardado em
`/etc/systemd/system/rayka.service.bak`; se o serviço novo não subir, o script restaura o antigo. (O script tem uma janela de ~1 minuto de
reinício, como o `install.sh`.)

Depois disso:
- **não use mais** `infra/install.sh` (ele reinstalaria o serviço velho, apontando para dentro do repositório);
- o `backend/.env` do repositório deixa de ser lido pelo servidor; **a cópia valendo é `/srv/rayka/shared/backend.env`** (edite lá e rode `sudo systemctl restart rayka`);
- publicar app à mão (`infra/publish-apps.sh`) precisa de `DOWNLOADS_DIR=/srv/rayka/shared/downloads`; o `downloads/manifest.json` versionado no repositório é só a semente inicial.

### 3. Instalar o runner self-hosted no servidor
Em Settings → Actions → Runners → New self-hosted runner (Linux x64) o GitHub mostra os comandos com o token. Use a etiqueta `rayka-prod`:
```bash
mkdir ~/actions-runner && cd ~/actions-runner
# baixe e extraia o pacote que a tela do GitHub indica, depois:
./config.sh --url https://github.com/Grs2080w/raykookie --token TOKEN_DA_TELA --labels rayka-prod --name rayka-server --unattended
sudo ./svc.sh install "$USER" && sudo ./svc.sh start
```
Requisitos na máquina: `git`, `rsync`, `curl`, `gzip`, `pg_dump` (pacote `postgresql-client`; já é a versão 17 aqui) e o Node (o workflow instala o 22 sozinho).
Em Settings → Actions → General → *Fork pull request workflows*, exija aprovação: o runner self-hosted executa o código do workflow na sua máquina.

### 4. Proteger a `main` (Settings → Branches → Add rule)
- *Require a pull request before merging*;
- *Require status checks to pass*: marque **`ci-ok`** (é o único; ele já exige todos os outros);
- (Opcional) *Require branches to be up to date*.

### 5. Primeira execução
Actions → **CD** → *Run workflow* com `apps = both` e `web = deploy`. Acompanhe o job `deploy`. Confira em
`https://raykookie.kntap.com.br` e no download dos apps.

## Dia a dia

- **Mudou o código:** abra um PR → o CI roda → merge na `main` → o CD publica o que mudou. Nada a rodar à mão.
- **Nome da versão dos apps:** é o `version: X.Y.Z` do `pubspec.yaml` (edite e faça o commit). O número do build (`+N`) quem põe é o pipeline.
- **Forçar publicação:** Actions → CD → *Run workflow* (escolha `apps` e `web`).
- **Voltar o site/API:** Actions → **Rollback** → *Run workflow* (vazio = release anterior). O banco não volta; os backups ficam em `/srv/rayka/backups`.
  Pelo terminal: `bash infra/ci/rollback.sh`.
- **Voltar um app:** apague o APK ruim de `/srv/rayka/shared/downloads` e ajuste `manifest.json` para a versão anterior (os 3 últimos ficam guardados;
  as Releases do GitHub guardam todos). Os aparelhos que já atualizaram só voltam se você publicar um build **maior** com o código antigo.
- **Falhou?** O GitHub avisa por e-mail. Os logs de cada passo ficam na aba Actions; no servidor: `journalctl -u rayka -n 100`.

## Testando os scripts na sua máquina (sem GitHub)
```bash
node --test infra/ci/publish-prebuilt.test.mjs        # registro de APK no manifesto
bash infra/ci/changes.test.sh                         # o que cada tipo de mudança aciona
bash infra/ci/deploy.test.sh                          # deploy, rollback automático, poda e recusas (projeto de mentira)
shellcheck -S warning -x infra/ci/*.sh                # lint dos scripts
actionlint                                            # lint dos workflows (brew install actionlint)
ANDROID_CERT_SHA256=<fingerprint> bash infra/ci/android-signing.sh verify downloads/<apk>   # a trava de assinatura
bash infra/check.sh --fast                            # a verificação local de sempre
```

## Custo e limites
- Repositório privado, plano gratuito: **2.000 minutos/mês** de runner Linux na nuvem. Uma execução do CI leva ~15–20 min de runner somados
  (jobs em paralelo), e uma publicação de app soma ~10 min por app. Se apertar: reduza as execuções (agrupe commits em PRs), ou
  desligue o job `android-check` para PRs. O runner self-hosted não gasta minutos.
- Os builds usam cache de dependências (npm, pub, Gradle); a primeira execução é a mais lenta.
- Artefatos de build ficam 14 dias; as Releases do GitHub guardam os APKs publicados.

## Limitações conhecidas
- Só Android. Os projetos têm pasta `ios/`, mas publicar no iOS exige conta Apple Developer e runner macOS (pago); não está no pipeline.
- Migrations devem continuar **aditivas** (o rollback de código roda no banco já migrado).
- `flutter analyze` falha em avisos e erros, mas tolera os ~33 *infos* de estilo antigos do app de gestão (`--no-fatal-infos`).
- O site e a API continuam no mesmo processo/porta (10005), como antes; o deploy reinicia o serviço (alguns segundos de indisponibilidade).
- O pipeline em si (workflows, runner, assinatura no GitHub) só pôde ser verificado por lint (`actionlint`/`shellcheck`) e pelos testes dos scripts: a
  primeira execução real acontece quando este PR for aberto (o CI) e quando você disparar o CD (o deploy).
