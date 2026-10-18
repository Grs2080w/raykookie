# Endereços públicos

Os três endereços moram em **`config/urls.json`**, a fonte única:

| Chave | O que é | Hoje |
|---|---|---|
| `SITE_URL` | site e app do cliente | `https://raykookie.com.br` |
| `GESTAO_HOST` | painel de gestão (só o host) | `business.raykookie.com.br` |
| `API_BASE` | API (container `raykookie-api`) | `https://api.raykookie.com.br` |

## Quem lê o arquivo

| Consumidor | Como |
|---|---|
| Frontend (Vercel) | `frontend/lib/urls.ts` e `frontend/next.config.mjs` (rewrite de `/api/*`). Variáveis `SITE_URL`, `GESTAO_HOST`, `API_BASE` na Vercel, se existirem, valem mais |
| Apps Flutter | CD e `infra/publish-apps.mjs` passam `--dart-define=API_BASE` e `SITE_BASE`. O padrão de `mobile-common/lib/core/config.dart` (só dev) é conferido por `mobile-common/test/urls_config_test.dart` |
| API Java | `infra/ci/run-api.sh` injeta `SITE_URL` no container |
| CD | `infra/urls.sh` (`bash infra/urls.sh API_BASE`) |

Mudar `config/urls.json` dispara o pipeline inteiro (`infra/ci/changes.sh`): rebuild dos APKs, recriação do container e deploy do front.

## Para trocar um endereço

1. Edite `config/urls.json` (o padrão de `config.dart` é conferido por teste; o de `SITE_URL` em `application.yml` e nos `@Value` do Java só vale em dev, pois em produção o `run-api.sh` injeta o do arquivo) e faça merge na `main`.
2. **Fora do código:** DNS/Cloudflare e Vercel (domínios), hosts do `rproxy` (`/home/g_snts/works/reverse-host/services.toml`, depois reiniciar o serviço) e o túnel do `cloudflared`, Google Console (redirect URI `{SITE_URL}/api/cliente/google/callback`, origens e domínio autorizado) e o webhook do Mercado Pago (`{SITE_URL}/api/webhooks/mercadopago`).
3. APKs já instalados só passam a usar o endereço novo quando houver uma versão nova; mantenha o host antigo vivo (alias no `rproxy`) enquanto isso.
