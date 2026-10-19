# Testes de segurança

Testes que **atacam de verdade** a API e o site e provam que a defesa responde (401/403/400/429, texto tratado como
texto, valores recalculados no servidor). Rodam sempre contra uma instância **isolada** (Postgres descartável via
Testcontainers + Mercado Pago/Firebase simulados). **Nunca** contra a produção.

## Como rodar

```sh
tests/security/run.sh        # backend (Java) + site (Next.js)
```

Ou separado:

```sh
cd backend-java && ./mvnw -pl app -am verify -Djacoco.skip=true -Dtest=NoSuchTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='Security*IT,AuditLogIT' -Dfailsafe.failIfNoSpecifiedTests=false
node --test tests/security/frontend-headers.test.mjs
```

## Mapa: categoria → teste

| # | Categoria | Onde | O que tenta |
|---|-----------|------|-------------|
| 1 | Injeção de SQL | `backend-java/app/src/test/java/br/com/kntap/raykookie/e2e/SecuritySqlInjectionIT.java` | `' OR '1'='1`, `'; DROP TABLE`, `UNION SELECT`, `pg_sleep` (cego por tempo) em query, caminho, corpo e login; tabelas intactas, nada de 500, nada de erro do banco no corpo, nada de atraso |
| 2 | Cabeçalhos, CORS, erros | `SecurityHeadersIT.java` + `tests/security/frontend-headers.test.mjs` | nosniff, X-Frame-Options, CSP, Referrer-Policy, HSTS; CORS fechado (inclusive preflight e `Origin: null`); `Cache-Control` em resposta autenticada; erros sem stack trace |
| 3 | Autenticação e autorização | `SecurityAuthIT.java` | sem token, token inventado, expirado, de cliente, na URL, `Bearer` vazio; logout invalida; usuário inexistente = senha errada; força bruta (429); cookie HttpOnly/SameSite/Secure; open redirect (`next`); path traversal |
| 4 | Golpes e abusos | `SecurityBusinessAbuseIT.java` | quantidade negativa/zero/fracionada/enorme/texto; item inexistente; mass assignment (`price`, `total`, `status:"pago"`, `customer_id`…); desconto forjado e cupom empilhado; "pagar" sem pagar; webhook sem assinatura; cliente escrevendo dados de gestão |
| 3/4 | Já cobertos por testes do projeto | `AccessMatrixIT` (toda rota × sem sessão/cliente/dona e IDOR entre clientes), `AbuseIT` (XFF forjado, cadastro fechado, upload gigante, limites de pedido/cartão), `WebhookAndPixIT` (assinatura, valor menor, pagamento de outro pedido, duplicado, corrida) | — |
| F2 | Histórico de auditoria | `AuditLogIT.java`, `AuditSupportTest.java`, `mobile-server/test/history_test.dart`, `mobile-server/test/venda_test.dart` | cada ação gera a linha certa; mesma transação (rollback leva o histórico junto); somente inserção (UPDATE/DELETE/TRUNCATE recusados, sem rota de escrita); só a dona lê; sem segredos |

## Rotas públicas (intencionais)

Qualquer outra rota exige sessão da dona (`OWNER`), do cliente (`CUSTOMER`), token de link de pagamento ou assinatura do
Mercado Pago. O `AccessMatrixIT` falha se uma rota deixar de declarar `@Access` (e o `ArchitectureTest` também).

Veja `docs/SEGURANCA.md`.
