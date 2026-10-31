# Arquitetura

Produção (dois deploys independentes):

```
 navegador ──▶ Vercel (frontend/, raykookie.com.br) ── /api/* (next.config.mjs) ──┐
                                                                                    ▼
 app Flutter ─────────────────────────────▶ container Docker raykookie-api (backend/server.mjs,
                                             api.raykookie.com.br) ───▶ PostgreSQL
```

`backend/server.mjs` também sabe servir o build do `frontend/` no mesmo processo (`/api/*` → API, o resto → site)
quando `FRONTEND_DIR` aponta para um frontend buildado; sem essa variável (como na imagem Docker) responde só a API.
Esse modo de processo único é o que `backend/tests/e2e/` exercita.

## Pastas

| Pasta | O que é |
|---|---|
| `backend/` | API em Next.js (somente rotas `app/api`), regras de negócio, acesso ao banco, `migrations/` e `scripts/`. Em produção o `server.mjs` dele também entrega o build do frontend. |
| `frontend/` | Telas em Next.js: landing, loja do cliente (`/cliente/*`) e painel (`/dashboard`, `/estoque`, …). Não acessa o banco: busca tudo na API (chamada ao próprio processo). No desenvolvimento, repassa `/api/*` ao backend de desenvolvimento. |
| `shared/` | TypeScript puro usado pelo frontend: cálculo de preço, regras/validação do pedido, leitura de números pt-BR, tipos do contrato da API. |
| `backend/shared/` | Cópia desses módulos para o backend, que é autocontido (nada fora de `backend/` é usado). Mudou uma regra em `shared/`? Copie também para `backend/shared/` — `backend/tests/isolation.test.ts` impede que o backend volte a importar de fora. |
| `mobile-client/` | App Flutter do **cliente**: cardápio (landing), login, pedidos e novo pedido. Pacote `br.com.kntap.raykookie`. |
| `mobile-server/` | App Flutter de **gestão**: dashboard, estoque (com leitura de nota fiscal por QR code), receitas, pedidos, metas, categorias e custos, com login por biometria. Pacote `br.com.kntap.raykookie_gestao`, ícone escuro. |
| `mobile-common/` | Pacote Dart com o que os dois apps dividem (cliente da API, sessão, modelos, preço, validação de pedido, tema e widgets). |
| `downloads/` | APKs publicados e `manifest.json` (versão/build/tamanho/SHA-256 de cada app). |
| `infra/` | Scripts de build/instalação/verificação e `infra/ci/` (pipeline). |
| `backend/tests/e2e/` | Testes de fumaça do `backend/server.mjs` (sobe o servidor de verdade e confere roteamento, saúde e desligamento). |
| `docs/` | Esta documentação. |
| `raykookie-arts/` | Logos e referências visuais (fonte de design, não usada pelo build; `referencias-designs/` fica fora do git). |

## Decisões

- **Mesma origem para o navegador.** Em produção o site (Vercel) reescreve `/api/*` para a API, então Google OAuth e cookies funcionam como num domínio só, sem CORS. Localmente, `backend/server.mjs` ainda pode subir backend e frontend num processo e decidir pela URL (`/api/*` ou o resto).
- **Backend sem telas, frontend sem banco.** O frontend (e o app) só conhecem o contrato em `docs/API.md`. O que vale para o site vale para o app.
- **Duas contas separadas.** Confeiteira (`users`/`sessions`) e cliente (`customers`/`customer_sessions`). Tokens não se misturam.
- **Sessão por cookie (web) ou Bearer (mobile).** O mesmo token de sessão; o mobile o recebe no corpo do login (`x-client: mobile`).
- **Preço sempre no servidor.** O cliente (site ou app) nunca define preço: o backend lê da receita no momento do pedido.
- **Cardápio = receitas do painel.** Só nome, preço por unidade e foto saem do painel; ingredientes, custos e margem nunca.
- **Dois apps, um código comum.** A loja e a gestão são apps separados (públicos diferentes, permissões diferentes), mas dividem o pacote `mobile-common`. O app do cliente não tem nenhuma entrada para o painel.
- **Versão explícita e publicação automática.** O `version:` do pubspec (nome+build) é a fonte da verdade; `infra/publish-apps.sh` recompila só o que mudou, assina com a chave de release, grava `downloads/` e o manifesto. O site e os apps leem o manifesto.
- **Regras duplicadas com testes iguais.** O Dart não compartilha código com o TypeScript, então `pricing`, validação do pedido e leitura de números existem nos dois, cobertos pelos mesmos casos de teste.

## Banco

Leitura de nota fiscal: o app de gestão baixa a página da NFC-e (link do QR code) e envia o HTML para `POST /api/nota/ler`; o servidor não faz requisições a portais externos (sem risco de SSRF e sem bloqueio por IP do servidor). Tabelas `receipt_imports` (trava de nota repetida) e `receipt_aliases` (apelidos item da nota → ingrediente).

Migrations em `backend/migrations/*.sql`, aplicadas em ordem por `npm run migrate` (idempotente, uma transação por arquivo).

## Portas

| Uso | Porta |
|---|---|
| Produção (API + site, um processo) | 10005 |
| Desenvolvimento: API (`npm run dev` em `backend/`) | 10006 |
| Desenvolvimento: site (`npm run dev` em `frontend/`, repassa `/api` a 10006) | 10005 |

As portas 10000–10004, 10008 e 10009 já são de outros serviços desta máquina (a 10004 é do wraap).
