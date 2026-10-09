# Reconstrução do backend em Java (Clean Architecture modular) — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Substituir a API Node/Next.js de `backend/` por um servidor Java em `backend-java/`, modular e em Clean Architecture, que mantém o contrato HTTP atual (site na Vercel e APKs instalados continuam funcionando sem mudança) e já corrige os 31 problemas de código/arquitetura (G01–G31) e os 13 problemas de testes (T01–T13) catalogados abaixo.

**Architecture:** Um projeto Maven multi-módulo. Cada domínio de negócio (identidade, clientes, catálogo, pedidos, pagamentos, devoluções…) é um módulo Maven próprio com as camadas `domain` → `application` → `adapter`, e só conversa com outros módulos pelo pacote `api` deles (interfaces, DTOs e eventos). Um módulo `app` monta tudo com Spring Boot. A fronteira entre módulos é verificada em teste (ArchUnit) e pelo grafo de dependências do Maven (sem ciclos).

**Tech Stack:** Java 25 (LTS) · Spring Boot 4.0.x (Spring MVC, threads virtuais) · Spring JDBC `JdbcClient` com SQL explícito · HikariCP · Flyway · Jackson · Firebase Admin SDK · Google API Client (verificação local do id_token) · JUnit 5 · AssertJ · Testcontainers (PostgreSQL 17) · WireMock · REST-assured · jqwik · ArchUnit · JavaParser (teste de estilo) · JaCoCo · Pitest · Checkstyle · Logback + logstash-logback-encoder · Micrometer/Actuator · Docker (eclipse-temurin:25-jre).

**Spec:** este documento. A seção "Catálogo de achados" é a especificação: cada item foi levantado na revisão de 2026-10-07 do `backend/` Node (branch `worktree-resolve-gaps`). Os arquivos Node citados são a referência de comportamento.

---

## Global Constraints

- Linguagem: Java 25, compilado com `--release 25` (a máquina tem JDK 26 e Maven em `/home/linuxbrew/.linuxbrew/bin/mvn`; o projeto usa Maven Wrapper `./mvnw`).
- Pasta: tudo em `backend-java/`. **Nada** dentro dela pode referenciar arquivo fora dela (mesma regra de isolamento do `backend/`, verificada em teste). Build: `./mvnw -f backend-java/pom.xml verify`.
- O `backend/` Node **continua em produção até o corte** (Fase 7). Nenhuma tarefa antes da Fase 7 altera `backend/`, `frontend/`, `shared/`, `downloads/` ou o banco de produção (exceção: Tarefas 0.1 e 0.2, que só mexem em testes e, se aprovado, em hotfixes do Node).
- Banco: o **mesmo PostgreSQL** e o mesmo esquema (migrations Node `001`–`021`). O Java usa Flyway com `baselineVersion=21` e só adiciona migrations **aditivas** (`V22+`: tabelas/colunas/índices novos, nunca `DROP`/`RENAME`/mudança de tipo). Assim o Node continua funcionando no mesmo banco e o corte tem volta.
- Contrato HTTP congelado: mesmos caminhos (`/api/...`), métodos, status, corpo JSON, nomes de campos, mensagens em português, cookies (`rayka_session`, `rk_customer`, `rk_oauth`), cabeçalhos (`authorization: Bearer`, `x-client: mobile`, `x-signature`, `x-request-id`, `x-jobs-secret`). Toda mudança intencional de contrato vem de um gap (G0x) e é marcada no teste com `@ChangedFromNode("G0x")`.
- Compatibilidade de credenciais: senhas `scrypt$<sal hex>$<hash hex>` (N=16384, r=8, p=1, 64 bytes, senha em UTF-8); sessões guardadas como `sha256(token)` em hex; tokens de 32 bytes em base64url; validade de 30 dias. Sessões e senhas existentes **têm de continuar válidas** depois do corte.
- Porta vinda de `PORT` (padrão 10005; **produção usa `PORT=20010`**, conferido no container `raykookie-api` em 2026-10-07 — o README está desatualizado nisso); downloads em `/app/downloads` na imagem; JSON da conta de serviço do Firebase em `FIREBASE_SERVICE_ACCOUNT_PATH`.
- Banco de teste **idêntico ao de produção**: imagem `postgres:17.11` (versão do cluster de produção, conferida com `pg_lsclusters`), fuso `America/Sao_Paulo`, e o esquema criado aplicando **as mesmas 21 migrations SQL de produção** (`backend/migrations/001`–`021`), copiadas byte a byte para `backend-java/` e verificadas por hash no CI. Nunca um esquema reescrito à mão nem um "snapshot" aproximado.
- Sistemas externos testados do jeito mais fiel possível (seção "Fidelidade dos sistemas externos"): o Mercado Pago é testado em três camadas — gravações de respostas **reais** do sandbox reproduzidas no CI, uma suíte **ao vivo** contra o sandbox com credenciais de teste, e vetores de assinatura gerados pelo algoritmo documentado.
- Fuso da loja: `America/Sao_Paulo`. Datas de negócio (`hoje`, `mês`) sempre nesse fuso.
- Dinheiro: centavos em `long` dentro do domínio (`Money`); `NUMERIC(…,2)` no banco; `BigDecimal` só na borda (JDBC e JSON). Proibido `double`/`float` para dinheiro.
- Identificadores em inglês; comentários, Javadoc, mensagens e nomes de teste (`@DisplayName`) em português.
- **Padrão de código obrigatório** (pedido explícito do dono do projeto, verificado pelo `CodeStyleTest` da Tarefa 1.2):
  1. Toda classe, record, interface, enum, construtor e método (inclusive `private`) tem Javadoc explicando o propósito, cada `@param` e o `@return`.
  2. O corpo de qualquer método com mais de 6 instruções é dividido em **blocos lógicos** separados por uma linha em branco.
  3. Cada bloco lógico começa com um comentário no formato `// ── N. <o que este bloco faz e, quando não for óbvio, por quê>` (numerado a partir de 1 dentro do método; ex.: `// ── 2. Recusa pagamento que não é deste pedido.`).
  4. Uma instrução por linha; nada de cadeias de ternários; métodos com no máximo 40 linhas e linhas com no máximo 120 colunas.
- Versões: usar a versão estável mais recente de cada dependência **no momento da execução**, respeitando os mínimos: Spring Boot ≥ 4.0.0, Testcontainers ≥ 1.21, JaCoCo ≥ 0.8.13 (suporte a Java 25), ArchUnit ≥ 1.4, JavaParser ≥ 3.26, WireMock ≥ 3.9, REST-assured ≥ 5.5, jqwik ≥ 1.9, Pitest ≥ 1.17, Checkstyle ≥ 10.20. A Tarefa 1.1 confere no Maven Central e fixa as versões no `pom.xml` raiz.

## Review Focus

1. **Duas confirmações do mesmo pedido ao mesmo tempo** (webhook + cartão + conciliação + cancelamento): exatamente um "pago", e qualquer segunda cobrança aprovada é devolvida sozinha. Teste: Tarefa 5.3, `ConfirmPaymentConcurrencyIT`.
2. **Sessão criada pelo Node usada no Java** (cookie e Bearer) e senha criada pelo Node: precisam funcionar sem novo login. Teste: Tarefa 3.1, `ScryptPasswordHasherTest` + `NodeSessionCompatibilityIT`.
3. **Cabeçalho `X-Forwarded-For` forjado:** nunca muda o IP usado no limite de tentativas. Teste: Tarefa 2.4, `ClientIpResolverTest`.
4. **Corpo de requisição enorme** (foto de 50 MB, JSON de 50 MB): recusado com 413 antes de ser lido para a memória. Teste: Tarefa 2.3, `RequestSizeLimitIT`.
5. **Valor em reais vindo de texto pt-BR** (`"1.500,50"`, `"12,5"`, `"-3"`, `"abc"`): conversão exata para centavos, ou erro 400 em português — nunca arredondamento silencioso. Teste: Tarefa 2.6, `MoneyParsingPropertyTest`.

---

## Catálogo de achados (a especificação)

### Código e arquitetura (G01–G31)

| ID | Problema no Node (arquivo) | Como o Java resolve |
|---|---|---|
| G01 | `POST /api/auth/register` aberto a qualquer pessoa; `/api/downloads` entrega o APK de gestão a qualquer conta (`app/api/auth/register/route.ts`, `app/api/downloads/route.ts:9`) | Cadastro de dona desligado (`REGISTRATION_ENABLED=false` por padrão; só funciona se a tabela `users` estiver vazia). Acesso de dona exige `user.id == shopOwnerId`. APK de gestão só para a dona. |
| G02 | Cartão aprovado em pedido cancelado vira "pago" sem estorno (`lib/card-pay.ts:41`) | Toda confirmação passa pelo `ConfirmPaymentUseCase` único; pedido cancelado + pagamento aprovado ⇒ devolução automática. |
| G03 | Duas aprovações paralelas: a segunda cobra e não registra; chave de idempotência repetida (`lib/card-pay.ts`) | Tabela `payment_attempts` com uma tentativa "em andamento" por pedido (índice único parcial) e chave de idempotência = UUID da tentativa; aprovação duplicada ⇒ devolução automática + alerta. |
| G04 | Limite de cartão só por pedido ⇒ teste de cartões roubados criando vários pedidos | Limites por cliente e por IP (falhas de cartão em 24 h) além do limite por pedido. |
| G05 | Limite de tentativas em memória, chaveado pelo `x-forwarded-for` cru e sem limpeza (`lib/auth.ts:73-82`) | `ClientIpResolver` com proxies confiáveis; contadores no Postgres (`rate_limits`) com janela fixa e limpeza diária. |
| G06 | Pool `pg` sem `on('error')` e sem tempo limite (`lib/db.ts`) | HikariCP com `connectionTimeout`, `statement_timeout`, validação; health com tempo limite. |
| G07 | `tx()` perde o erro original se o `ROLLBACK` falhar e devolve conexão quebrada (`lib/db.ts:18-30`) | Transações do Spring (`@Transactional` / `TransactionTemplate`); teste prova rollback e descarte da conexão. |
| G08 | Uploads lidos inteiros antes de checar tamanho (`recipes/[id]/photo`, `refund-form.ts`, `feedback-form.ts`) | Limites de multipart do Spring + `RequestSizeLimitFilter` que recusa pelo `Content-Length` e limita o fluxo. |
| G09 | Cancelamento com Pix recém-pago marca pago sem conferir valor/id (`lib/cancel.ts:29-31`) | Usa o `ConfirmPaymentUseCase` (mesmas guardas do webhook). |
| G10 | Número de pedido manual sem trava ⇒ colisão com pedido da loja (`app/api/orders/route.ts:11`) | `OrderNumberAllocator` único com `pg_advisory_xact_lock(owner)` para os dois caminhos, mais nova tentativa ao violar a unicidade. |
| G11 | Pedidos e Pix sem limite por cliente | Limite de pedidos por cliente/hora e de pedidos sem pagamento em aberto. |
| G12 | Dinheiro em `float` (`parseFloat` de NUMERIC, `::float8`) | `Money` em centavos `long`; leitura `BigDecimal` com escala 2; testes exatos. |
| G13 | Jobs por "janela de minutos" a partir de `setInterval` (`app/api/jobs/tick/route.ts`) | `@Scheduled(cron, zone=America/Sao_Paulo)` + `JobRunner` com trava (advisory lock) e tabela `job_runs` que recupera execuções diárias perdidas. |
| G14 | Segredo dos links de download cai num valor derivado da `DATABASE_URL` (`lib/downloads.ts:29`) | `DOWNLOAD_SECRET` obrigatório (≥ 32 caracteres) — o servidor não sobe sem ele. |
| G15 | Nenhuma validação do ambiente na subida; comentários na mesma linha do `.env` quebram `COOKIE_SECURE` | `@ConfigurationProperties` + `@Validated`; booleanos aceitam só `true`/`false`; erro claro na subida. `.env.example` sem comentário na mesma linha. |
| G16 | Sessões vencidas nunca apagadas | Job diário `purge-expired-sessions` (dona e cliente). |
| G17 | Falha única de inicialização do Firebase desliga o push até reiniciar (`lib/push.ts:103`) | Inicialização preguiçosa com nova tentativa a cada 10 min. |
| G18 | Webhook não confere a idade do `ts` da assinatura | Rejeita `ts` com mais de 10 min de diferença (configurável). |
| G19 | Mensagens que revelam se o e-mail tem conta | Login do cliente responde sempre "E-mail ou senha incorretos." (com dica fixa sobre o Google); cadastro duplicado mantém 409 com limite de tentativas (risco aceito e documentado). |
| G20 | Rotas públicas devolvem HTML do Next em erro | `@RestControllerAdvice` global: todo erro (400/401/403/404/405/409/413/429/500/503) sai como `{"error": "..."}`. |
| G21 | Next.js inteiro para servir JSON; autenticação presa a `headers()/cookies()` | Spring MVC; casos de uso recebem `OwnerId`/`CustomerId`, nunca a requisição. |
| G22 | SQL dentro de 28 das 86 rotas; validação manual | Camadas: `adapter.in.web` (DTO + validação) → `application` (caso de uso) → `domain` → `adapter.out.persistence` (SQL). ArchUnit impede atalhos. |
| G23 | Tudo assume uma instância (limite em memória, jobs no processo, estado global) | Estado compartilhado só no Postgres (limites, travas de job, sessões). Servidor sem estado. |
| G24 | "Multi-dono" pela metade; loja depende do *nome* `SHOP_OWNER` | Decisão: **uma única dona**. `SHOP_OWNER` é resolvido para um id na subida (o servidor não sobe se não existir); só esse id acessa o painel. |
| G25 | Mesma regra em três lugares (`shared/`, `backend/shared/`, Dart) sem verificação | Regras no domínio Java + **vetores de paridade** em JSON (`backend-java/rules-vectors/`) gerados a partir do TypeScript e usados nos testes Java; o CI compara com cópias usadas pelos testes TS e Dart. |
| G26 | Contrato sem versão; `docs/API.md` manual | `/api` congelado como v1 e protegido pela suíte de contrato; OpenAPI gerado (springdoc) e publicado em `/api/openapi.json`; mudança incompatível só em `/api/v2`. |
| G27 | Fotos guardadas no Postgres | Porta `PhotoStore` com adaptador Postgres (compatível no corte); adaptador em disco numa fase posterior (Tarefa 8.1, opcional). |
| G28 | Só `console.error`; nenhum alerta | Logs JSON com `requestId`; métricas Micrometer; `OpsAlert` (log `ERROR` com marcador + contador + webhook opcional `ALERT_WEBHOOK_URL`) em falha de pagamento, devolução e job. |
| G29 | Migrations sem trava e sem desfazer | Flyway (trava própria, versão, checksum) + política "expandir/contrair" + backup obrigatório no script de deploy. |
| G30 | `uncaughtException: Controller is already closed` nos logs de produção (2026-10-07): o stream do APK (`app/api/downloads/[app]/route.ts`, `Readable.toWeb`) explode quando o cliente aborta o download, e um erro não tratado pode derrubar o processo | Download com `StreamingResponseBody`; aborto do cliente (`ClientAbortException`/`IOException: Broken pipe`) é tratado e só registrado em `DEBUG`; teste aborta no meio e confere que o servidor segue saudável. |
| G31 | Configuração de produção incompleta, conferida no container em 2026-10-07: **`MP_PUBLIC_KEY` ausente** (por isso o cartão responde 503 "O pagamento com cartão não está disponível agora" e o site/app não conseguem tokenizar — causa mais provável de o cartão "não funcionar") e **`DOWNLOAD_SECRET` ausente** (G14 ativo) | Validação na subida (Tarefa 2.6): com `PAYMENTS_CARD_ENABLED=true`, `MP_PUBLIC_KEY` e `MP_ACCESS_TOKEN` são obrigatórios e precisam ser do **mesmo ambiente** (ambos de teste ou ambos de produção — conferido chamando `GET /users/me` com o token na subida); a Tarefa 0.3 corrige a produção atual. |

### Testes (T01–T13)

| ID | Problema | Como o plano resolve |
|---|---|---|
| T01 | Testes do servidor ficaram fora de `backend/` (`tests/server/`) | Tarefa 0.1 move para `backend/tests/e2e/`; no Java, todo teste mora dentro de `backend-java/`. |
| T02 | Rotas chamadas como funções, não pelo HTTP | Suíte de contrato caixa-preta (`backend-java/contract-tests`) por HTTP real; testes de módulo com servidor em porta aleatória. |
| T03 | Login nunca testado de verdade (`getUser` falso) | `AccessInventoryTest` (toda rota declara acesso, senão falha) + `AccessMatrixIT` (sem sessão → 401; outra conta → 404) para todas as rotas. |
| T04 | Banco simulado nos fluxos de dinheiro | Casos de uso de pagamentos/devoluções/pedidos testados só com Postgres real (Testcontainers). Proibido simular repositório nesses módulos (regra ArchUnit de teste). |
| T05 | Teste que protege a cobrança dupla (`card-pay.test.ts:46`) | Testes novos que exigem devolução nos casos G02/G03 — escritos antes da implementação. |
| T06 | Sem testes de concorrência fora do link de pagamento | Testes com `CountDownLatch` + vários threads para: cartão duplo, cancelamento × webhook, número de pedido manual × loja. |
| T07 | Sem testes de abuso | `AbuseIT`: XFF forjado, cadastro fechado, APK de gestão, upload gigante, limite de pedidos/cartões. |
| T08 | Dinheiro com `toBeCloseTo`; sem paridade TS/Dart | Asserções exatas em centavos; jqwik com milhares de entradas; vetores de paridade (G25). |
| T09 | Sem testes de resiliência | Banco fora (container pausado) → 503 rápido; MP lento (WireMock com atraso) → tempo limite; Firebase fora → pedido segue. |
| T10 | Migrations não testadas | `MigrationsIT`: Flyway em banco vazio reproduz o esquema Node + V22+; baseline sobre banco migrado pelo Node valida limpo. |
| T11 | Sem cobertura medida | JaCoCo com mínimo (geral 85 % de linhas; `payments`, `refunds`, `orders` 95 %) e Pitest ≥ 80 % de mutantes mortos em `kernel`, `payments.domain`, `refunds.domain`. |
| T12 | 100 testes pulados localmente sem banco | Testcontainers: `./mvnw verify` só precisa de Docker; nenhum teste é pulado. No Node, Tarefa 0.1 adiciona `docker compose` para o banco de teste. |
| T13 | O teste de sandbox do cartão (`backend/tests/card-pay.sandbox.test.ts`) **aceita como sucesso** o erro `Unauthorized use of live credentials` — rodou com credenciais de produção, nunca provou um pagamento com cartão e escondeu o problema | Suíte ao vivo do Mercado Pago (Tarefa 5.2-B) com verificação prévia: credencial que não é de teste **faz o teste falhar** com instrução de como gerar a credencial certa; nenhum `catch` que transforma erro em sucesso. |

---

## Estrutura de arquivos

```
backend-java/
├── pom.xml                         # pai: versões, plugins (checkstyle, jacoco, pitest), módulos
├── mvnw, .mvn/                     # Maven Wrapper
├── Dockerfile, .dockerignore
├── .env.example                    # sem comentários na mesma linha (G15)
├── config/checkstyle.xml
├── rules-vectors/                  # vetores de paridade JSON (G25)
│   ├── pricing.json  money-parse.json  refund-rules.json  shop-order.json
├── platform/
│   ├── kernel/                     # Money, ids, erros de domínio, Clock da loja, Result
│   ├── web/                        # tratador global de erros, limites de corpo, IP do cliente, requestId
│   ├── security/                   # sessão (cookie/Bearer), @Access, interceptor fail-closed
│   ├── persistence/                # DataSource/Hikari, Flyway, helpers JDBC, testes de migração
│   ├── ratelimit/                  # contadores no Postgres
│   ├── scheduling/                 # JobRunner + job_runs
│   ├── observability/              # logs JSON, métricas, OpsAlert
│   └── testing/                    # base de testes: Postgres Testcontainers, WireMock, fábricas de dados
├── modules/
│   ├── identity/                   # conta da dona e sessões
│   ├── customers/                  # conta do cliente, Google, LGPD, pedidos de privacidade
│   ├── notifications/              # push (Firebase) e aparelhos
│   ├── catalog/                    # ingredientes, receitas, fotos, categorias, custos fixos, ajustes, alertas de preço
│   ├── purchasing/                 # fornecedores, cotações, busca de preço, nota fiscal (NFC-e)
│   ├── storefront/                 # cardápio público, story, documentos legais, retirada, config pública
│   ├── orders/                     # pedidos manuais e da loja, numeração, eventos, cancelamento
│   ├── payments/                   # Mercado Pago, Pix, cartão, link de pagamento, webhook, confirmação única
│   ├── refunds/                    # devoluções, fotos, novas tentativas, conciliação
│   ├── feedback/                   # feedback do cliente
│   ├── insights/                   # dashboard e metas
│   └── releases/                   # APKs, manifesto, links assinados
├── app/                            # Spring Boot main, wiring, application.yml
└── contract-tests/                 # suíte caixa-preta por HTTP (roda contra Node e contra Java)
```

Cada módulo de negócio segue **exatamente** esta forma (exemplo `orders`):

```
modules/orders/
├── pom.xml
└── src/
    ├── main/java/br/com/kntap/raykookie/orders/
    │   ├── api/                    # ÚNICO pacote visível para outros módulos
    │   │   ├── OrdersApi.java      # interface de consulta/comando usada por outros módulos
    │   │   ├── events/             # records de eventos publicados (ex.: ShopOrderCreated)
    │   │   └── ports/              # portas que OUTROS módulos implementam (inversão de dependência)
    │   ├── domain/                 # entidades, value objects, regras puras (sem Spring, sem SQL)
    │   ├── application/            # casos de uso (1 classe por caso de uso) + portas de saída
    │   └── adapter/
    │       ├── in/web/             # controllers, DTOs de requisição/resposta, @Access
    │       └── out/persistence/    # repositórios JdbcClient (SQL explícito)
    └── test/java/...               # unitários (domain), integração (Testcontainers), web (HTTP real)
```

Grafo de dependências entre módulos (Maven, sem ciclos; seta = "depende de"):

```
identity → kernel
customers → identity(api)
notifications → kernel
catalog → kernel
purchasing → catalog(api)
storefront → catalog(api)
orders → catalog(api), storefront(api), customers(api), notifications(api)
payments → orders(api), notifications(api)
refunds → orders(api), payments(api), notifications(api)
feedback → orders(api), customers(api)
insights → orders(api), catalog(api)
releases → identity(api)
app → todos
```

Onde um módulo "de baixo" precisa de algo de um módulo "de cima" (ex.: `orders` precisa cancelar um Pix pendente, que é de `payments`), o módulo de baixo declara uma **porta** em `api/ports` e o módulo de cima a implementa. Exemplo: `orders.api.ports.PendingChargeCanceller` é implementada por `payments`.

---

## Padrão de código (modelo obrigatório)

Todo código segue este modelo. Ele é a referência do `CodeStyleTest` e da revisão de cada tarefa.

```java
package br.com.kntap.raykookie.payments.application;

/**
 * Confirma o pagamento de um pedido da loja. É o ÚNICO lugar do sistema que marca um pedido
 * como pago — webhook, cartão, cancelamento e conciliação passam todos por aqui (G02, G03, G09).
 */
public final class ConfirmPaymentUseCase {

    private final ShopOrderLocking orders;
    private final RefundRequester refunds;
    private final PaymentEvents events;

    /**
     * Cria o caso de uso com as portas de que ele precisa.
     *
     * @param orders  acesso ao pedido com trava de linha
     * @param refunds pede devolução quando o pagamento não pode ficar
     * @param events  publica os eventos de pagamento (notificações, auditoria)
     */
    public ConfirmPaymentUseCase(ShopOrderLocking orders, RefundRequester refunds, PaymentEvents events) {
        this.orders = orders;
        this.refunds = refunds;
        this.events = events;
    }

    /**
     * Aplica um pagamento aprovado ao pedido.
     *
     * @param orderId id do pedido da loja ({@code shop_orders.id})
     * @param payment pagamento consultado direto no gateway (nunca o corpo da notificação)
     * @return o desfecho: pago agora, já estava pago, devolvido ou recusado
     */
    @Transactional
    public ConfirmationOutcome confirm(ShopOrderId orderId, GatewayPayment payment) {

        // ── 1. Trava o pedido: avisos simultâneos do mesmo pagamento ficam em fila aqui,
        //       e só o primeiro enxerga o pedido ainda sem pagamento.
        ShopOrder order = orders.lockById(orderId)
                .orElseThrow(() -> new NotFoundException("Pedido não encontrado."));

        // ── 2. Recusa pagamento que não é deste pedido ou que não cobre o total.
        if (!order.ownsPayment(payment.id()) || payment.amount().isLessThan(order.total())) {
            events.publish(new PaymentRejected(orderId, payment.id()));
            return ConfirmationOutcome.REJECTED;
        }

        // ── 3. Pagamento repetido (já pago com OUTRO id): a segunda cobrança volta inteira.
        if (order.isPaid() && !order.paidWith(payment.id())) {
            refunds.refundWhole(orderId, payment.id(), RefundReason.DUPLICATE_PAYMENT);
            return ConfirmationOutcome.DUPLICATE_REFUNDED;
        }

        // ── 4. Reenvio do mesmo pagamento: nada muda (idempotente).
        if (order.isPaid()) {
            return ConfirmationOutcome.ALREADY_PAID;
        }

        // ── 5. Marca como pago e registra o evento na mesma transação.
        orders.markPaid(orderId, payment.id(), payment.amount());
        events.publish(new OrderPaid(orderId, payment.id(), payment.amount()));

        // ── 6. Pedido já cancelado: aceita o dinheiro só para devolvê-lo inteiro.
        if (order.isCancelled()) {
            refunds.refundWhole(orderId, payment.id(), RefundReason.PAID_AFTER_CANCEL);
            return ConfirmationOutcome.PAID_AFTER_CANCEL_REFUNDED;
        }
        return ConfirmationOutcome.PAID_NOW;
    }
}
```

Regras extras:
- Records de DTO e de domínio também têm Javadoc (com `@param` para cada componente).
- Exceções de negócio estendem `DomainException` (kernel) e carregam a mensagem em português que vai para o cliente.
- Nenhum `catch (Exception e)` vazio. Erro esperado de integração externa vira um tipo (`GatewayUnavailable`), registrado com `OpsAlert` quando afeta dinheiro.

---

## Fidelidade dos sistemas externos (como cada dependência é reproduzida nos testes)

Princípio: cada sistema de que o servidor depende é reproduzido nos testes **com o comportamento real dele**, não com uma imitação escrita à mão. Onde dá para rodar o próprio sistema (Postgres), roda o próprio sistema; onde é um serviço de terceiro (Mercado Pago, Google, Firebase), as respostas usadas no CI são **gravadas do serviço real** e existe uma suíte ao vivo para regravar e conferir.

| Sistema | Em produção | Nos testes do CI (determinístico) | Suíte ao vivo (`-Psandbox`, sob demanda + toda noite no CI) |
|---|---|---|---|
| PostgreSQL | cluster 17.11 em `127.0.0.1:5432` | Testcontainers `postgres:17.11`, fuso `America/Sao_Paulo`, esquema criado pelas **21 migrations reais** (`V1__001_init.sql` … `V21__021_card_payment.sql`, cópia byte a byte de `backend/migrations/`) + V22+; um container por suíte, dados limpos por transação desfeita no fim de cada teste | Ensaio com **cópia do banco de produção** restaurada num container `postgres:17.11` separado (Tarefa 7.2) |
| Mercado Pago (Pix, cartão, devoluções, webhook) | `api.mercadopago.com` com credenciais de produção | WireMock servindo **gravações reais do sandbox** (`src/test/resources/mp-recordings/`), capturadas pela suíte ao vivo em modo gravação; tokens e e-mails mascarados na gravação | Testes contra `api.mercadopago.com` com **credenciais de teste da aplicação** (Suas integrações → aplicação → Testes → Credenciais de teste) e **contas de teste** (vendedor e comprador separados); cartões de teste oficiais e nomes mágicos do titular (abaixo); tokenização real por `POST /v1/card_tokens?public_key=…`, como o site/app fazem |
| Webhook do Mercado Pago | notificação assinada (`x-signature`, `x-request-id`) | vetor de ouro do algoritmo + **uma notificação real** capturada no ensaio (Tarefa 7.2, "Simular notificação" no painel) salva como fixture | — |
| Google (login) | OAuth web + `id_token` do app | WireMock servindo JWKS com chave de teste; tokens assinados localmente com os mesmos campos do Google (`iss`, `aud`, `exp`, `email_verified`, `sub`) | Roteiro manual com conta Google de teste no ensaio (Tarefa 7.2) |
| Firebase (push) | FCM HTTP v1 | adaptador REST com URL base configurável apontando para WireMock, com os **corpos de erro reais** do FCM (`UNREGISTERED`, `INVALID_ARGUMENT`, `UNAVAILABLE`) | envio para um aparelho de teste (`FCM_TEST_DEVICE_TOKEN`) |
| Sites de fornecedores (Tray) | HTML público | WireMock com HTML **gravado** do site real (`tray-search.html` atual + regravação) | busca real de um produto conhecido |
| NFC-e | HTML enviado pelo app | fixtures de notas reais anonimizadas (as de `backend/tests/nfce.test.ts`) | — |
| Arquivos de APK | `downloads/` montado no container | diretório temporário com arquivos de tamanho real (40–60 MB, conteúdo aleatório) | — |

**Cartões e nomes de teste do Mercado Pago** (documentação oficial "Realizar compra teste", conferida em 2026-10-07; a Tarefa 0.3 confere de novo antes de usar, porque a documentação já trouxe números e validades diferentes entre páginas):

| Bandeira | Número | CVV | Validade |
|---|---|---|---|
| Mastercard | 5031 4332 1540 6351 | 123 | 11/30 |
| Visa | 4235 6477 2802 5682 | 123 | 11/30 |
| American Express | 3753 651535 56885 | 1234 | 11/30 |
| Elo (débito) | 5067 7667 8388 8311 | 123 | 11/30 |

| Nome do titular | Resultado esperado |
|---|---|
| `APRO` (CPF `12345678909`) | `approved` |
| `OTHE` (CPF `12345678909`) | `rejected` / `cc_rejected_other_reason` |
| `CONT` | `in_process` |
| `CALL` | `rejected` / `cc_rejected_call_for_authorize` |
| `FUND` | `rejected` / `cc_rejected_insufficient_amount` |
| `SECU` | `rejected` / `cc_rejected_bad_filled_security_code` |
| `EXPI` | `rejected` / `cc_rejected_bad_filled_date` |
| `FORM` | `rejected` / `cc_rejected_bad_filled_other` |

Campos que o servidor envia em `POST /v1/payments` com cartão, conforme os exemplos oficiais de "Envio de pagamentos": `token`, `transaction_amount`, `installments`, `payment_method_id`, **`issuer_id`** (o Node atual **não envia** — ver Tarefa 0.3), `description`, `external_reference`, `notification_url`, `payer.email`, `payer.first_name`, `payer.identification.type`/`number`, e o cabeçalho obrigatório `X-Idempotency-Key`.

---

## Fase 0 — Preparação no Node (não muda produção)

### Task 0.1: Mover os testes do servidor para dentro de `backend/` e dar um banco de teste local (T01, T12)

**Files:**
- Move: `tests/server/server.test.mjs` → `backend/tests/e2e/server.test.mjs`
- Create: `backend/docker-compose.test.yml`
- Modify: `backend/package.json` (scripts `test:e2e`, `test:db`), `infra/check.sh:25`, `README.md` (linha de `tests/server/`), `docs/ARQUITETURA.md` (linha de `tests/server/`)

**Interfaces:**
- Produces: `npm run test:e2e` (em `backend/`) e `npm run test:db` (sobe Postgres 17 em `127.0.0.1:54330`).

- [ ] **Step 1: Mover o arquivo e corrigir os caminhos.** `git mv tests/server/server.test.mjs backend/tests/e2e/server.test.mjs`. No arquivo, troque `const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');` por `const backendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');`, `backendDir` passa a ser `process.env.BACKEND_DIR || backendRoot` e `frontendDir` passa a exigir `FRONTEND_DIR_UNDER_TEST` (sem padrão `../frontend`); sem essa variável, a suíte "API + site no mesmo processo" é pulada com a mensagem `defina FRONTEND_DIR_UNDER_TEST para testar o modo API + site`.
- [ ] **Step 2: Excluir `tests/e2e` do vitest.** Em `backend/vitest.config.ts`, adicione `'tests/e2e/**'` ao `exclude`.
- [ ] **Step 3: Scripts.** Em `backend/package.json`: `"test:e2e": "node --test tests/e2e/server.test.mjs"`, `"test:db": "docker compose -f docker-compose.test.yml up -d --wait"`.
- [ ] **Step 4: `backend/docker-compose.test.yml`:**

```yaml
services:
  postgres-test:
    image: postgres:17
    environment:
      POSTGRES_USER: rayka_test
      POSTGRES_PASSWORD: rayka_test
      POSTGRES_DB: rayka_test
    ports: ["127.0.0.1:54330:5432"]
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "rayka_test"]
      interval: 2s
      retries: 20
```

- [ ] **Step 5: Rodar.** `cd backend && npm run build && npm run test:e2e` → esperado: todos passam (14 + 4 pulados do modo site). `npm run test:db && DATABASE_URL=postgres://rayka_test:rayka_test@127.0.0.1:54330/rayka_test npm run migrate && TEST_DATABASE_URL=postgres://rayka_test:rayka_test@127.0.0.1:54330/rayka_test npm test` → esperado: **0 skipped**.
- [ ] **Step 6: Atualizar `infra/check.sh`** para `( cd backend && npm run test:e2e )` e as linhas do README/ARQUITETURA. Rodar `bash infra/check.sh --fast`.
- [ ] **Step 7: Commit** `test(backend): move testes do servidor para backend/tests/e2e e adiciona banco de teste local`.

### Task 0.2 — NÃO EXECUTAR (decisão do dono em 2026-10-07): Hotfixes mínimos no Node

**Decisão:** o dono optou por não corrigir o Node. A reconstrução em Java deve levar cerca de 2 dias, e ele aceita manter as falhas de G01–G05 em produção durante esse período. Pular esta tarefa. O conteúdo abaixo fica só como referência, caso o corte atrase.

- [ ] G01: `app/api/auth/register/route.ts` responde 404 quando `process.env.REGISTRATION_ENABLED !== 'true'`; `app/api/downloads/route.ts` e `app/api/downloads/[app]/route.ts` só tratam como dona quem tem `user.username === (process.env.SHOP_OWNER || 'rayka')`.
- [ ] G02/G03: em `lib/card-pay.ts`, quando o `UPDATE` afeta 0 linhas com `status === 'approved'`, consultar o pedido: se já pago com outro id ou cancelado, chamar `createRefund(pay.paymentId, null, 'rayka-dup-' + pay.paymentId)` e registrar evento `pagamento_duplicado_devolvido`.
- [ ] G05: chave do limitador usa só o último IP do `x-forwarded-for` (o que o proxy local acrescenta) e o `Map` ganha limite de 10 000 chaves (apaga as vencidas ao passar disso).
- [ ] Teste do `card-pay.test.ts:46` reescrito: espera `createRefund` chamado.
- [ ] Commit por item; deploy manual seguindo o README.

### Task 0.3: Diagnóstico do cartão do Mercado Pago com evidência real (G31, T13)

O Pix funciona em produção; o cartão não funciona nem em produção nem nos testes. Esta tarefa descobre **por quê**, com o sandbox de verdade, antes de escrever o adaptador Java. O resultado é um relatório (`docs/mercadopago-cartao-diagnostico.md`) e uma suíte ao vivo verde — é a base das gravações da Tarefa 5.2.

**Ação do dono (bloqueia esta tarefa; ele vai fazer em casa):** pegar no painel do Mercado Pago (Suas integrações → aplicação da Raykookie → Testes / Contas de teste) e gravar em **`~/.raykookie/mercadopago.env`** (fora do repositório, `chmod 600`), exatamente com estes nomes:

```
# Conta real da loja (produção)
MP_PROD_PUBLIC_KEY=
MP_PROD_ACCESS_TOKEN=
MP_PROD_CLIENT_ID=
MP_PROD_CLIENT_SECRET=
MP_PROD_WEBHOOK_SECRET=
# Credenciais de teste da aplicação (Testes → Credenciais de teste)
MP_SANDBOX_PUBLIC_KEY=
MP_SANDBOX_ACCESS_TOKEN=
# Usuário de teste VENDEDOR: as credenciais de produção dele + dados da conta
MP_TEST_SELLER_PUBLIC_KEY=
MP_TEST_SELLER_ACCESS_TOKEN=
MP_TEST_SELLER_USER_ID=
MP_TEST_SELLER_USERNAME=
MP_TEST_SELLER_PASSWORD=
# Usuário de teste COMPRADOR
MP_SANDBOX_BUYER_EMAIL=
MP_TEST_BUYER_USERNAME=
MP_TEST_BUYER_PASSWORD=
```

Antes de começar a tarefa, o executor confere (sem exibir valores) que o arquivo existe, tem permissão 600 e que todas as chaves acima estão preenchidas; se faltar alguma, para e pede ao dono. **Nunca** copia valores para dentro do repositório, de logs ou de mensagens.

Credenciais encontradas em 2026-10-07 de sessões anteriores (para o dono confirmar, não para usar direto): `backend/.env` tem só produção sem Public Key; `~/.claude/jobs/4ba64159/tmp/prod_env_new.txt` tem a `MP_PUBLIC_KEY` de produção que nunca foi aplicada; `~/.claude/jobs/4ba64159/tmp/mp_probe3.py` tem as credenciais de produção do usuário de teste. Esses temporários têm permissão `-rw-rw-r--` e devem ser apagados depois que o `mercadopago.env` existir (com aprovação do dono).

**Qual par de credenciais a suíte ao vivo usa:** testar os dois caminhos que a documentação admite e registrar no relatório qual funciona para cartão: (a) `MP_SANDBOX_*` (credenciais de teste da aplicação); (b) `MP_TEST_SELLER_*` (credenciais de produção do usuário de teste vendedor), sempre com o comprador `MP_SANDBOX_BUYER_EMAIL`. Nas tarefas seguintes (3.1, 5.2-B, CI), `MP_SANDBOX_PUBLIC_KEY`/`MP_SANDBOX_ACCESS_TOKEN` passam a apontar para o par que funcionou.

Hipóteses, cada uma com a verificação que a confirma ou descarta (registrar o resultado de cada uma no relatório):

| # | Hipótese | Evidência hoje | Verificação |
|---|---|---|---|
| H1 | `MP_PUBLIC_KEY` não está configurada em produção ⇒ `mpCardConfigured()` falso ⇒ 503 e o site/app não tokenizam | **Confirmada** em 2026-10-07: a variável não existe no container `raykookie-api` | `GET https://raykookie-api.kntap.com.br/api/payment-config` deve trazer a chave pública; hoje não traz |
| H2 | Os testes rodaram com credenciais de produção (`APP_USR-` da conta real) e cartões de teste ⇒ `Unauthorized use of live credentials` | **Confirmada** pelo próprio comentário de `card-pay.sandbox.test.ts` | Rodar a suíte ao vivo com as credenciais de teste do dono |
| H3 | `issuer_id` não é enviado na criação do pagamento | **Confirmada no código** (`backend/lib/mercadopago.ts`, `createCardPayment`) | Com credenciais de teste: pagar `APRO` com e sem `issuer_id` (obtido de `GET /v1/payment_methods/installments?bin=…` → `issuer.id`) e comparar |
| H4 | E-mail do pagador inválido para o ambiente (o teste usa `teste@raykookie.com.br`; em teste o comprador deve ser a conta de teste, diferente do vendedor) | a confirmar | Pagar `APRO` com o e-mail da conta comprador de teste e com outro e-mail; anotar o erro exato |
| H5 | `payment_method_id` errado (a detecção de bandeira já foi corrigida uma vez, commit `6129571`) | a confirmar | Para os 4 cartões de teste, conferir que `CardFields.tsx` / `card_fields.dart` mandam o `payment_method_id` devolvido por `/v1/payment_methods/installments` |
| H6 | Tokenização no app/site usa uma chave diferente da do token de acesso do servidor (teste × produção misturados) | a confirmar | Token criado com a chave pública X só é aceito por pagamento com o access token do mesmo ambiente — testar a combinação errada e registrar o erro |

- [ ] **Step 1: Suíte ao vivo honesta (T13).** Reescrever `backend/tests/card-pay.sandbox.test.ts` sem o `try/catch` que aceita `Unauthorized use of live credentials`: antes de tudo, `GET https://api.mercadopago.com/users/me` com o token; se a conta não for de teste (a resposta de contas de teste traz `test_user` em `tags` — confirmar esse campo na primeira execução e registrar no relatório o JSON real, com dados pessoais mascarados), **falhar** com a mensagem `"MP_SANDBOX_ACCESS_TOKEN não é credencial de teste — gere em Suas integrações → Testes → Credenciais de teste"`. Usar os cartões e nomes da tabela de "Fidelidade dos sistemas externos", o e-mail `MP_SANDBOX_BUYER_EMAIL` e enviar `issuer_id`.
- [ ] **Step 2: Rodar** `set -a; . ~/.raykookie/mercadopago.env; set +a; cd backend && npx vitest run tests/card-pay.sandbox.test.ts` com `RUN_MP_SANDBOX=1`. Registrar no relatório o resultado de cada nome mágico e de H3–H6.
- [ ] **Step 3: Corrigir o Node só no que o diagnóstico provar** (com aprovação do dono, como na 0.2): enviar `issuer_id` se H3 se confirmar; configurar `MP_PUBLIC_KEY` de produção no `.env` (H1) — e só depois de um pagamento real de valor baixo feito pelo dono, devolvido em seguida pelo painel.
- [ ] **Step 4: Commit** `test(mercadopago): diagnóstico do cartão com sandbox real` (relatório + teste).

---

## Fase 1 — Esqueleto do projeto Java

### Task 1.1: Projeto Maven multi-módulo, wrapper e build verde

**Files:**
- Create: `backend-java/pom.xml`, `backend-java/.mvn/wrapper/maven-wrapper.properties`, `backend-java/mvnw`, `backend-java/mvnw.cmd`, `backend-java/.gitignore`
- Create: `backend-java/platform/kernel/pom.xml`, `backend-java/app/pom.xml`, `backend-java/app/src/main/java/br/com/kntap/raykookie/RaykookieApplication.java`, `backend-java/app/src/main/resources/application.yml`
- Test: `backend-java/app/src/test/java/br/com/kntap/raykookie/ApplicationStartsIT.java`

**Interfaces:**
- Produces: `groupId br.com.kntap.raykookie`; pacote base `br.com.kntap.raykookie`; comando `./mvnw -f backend-java/pom.xml verify`.

- [ ] **Step 1: Conferir versões.** Para cada dependência do Tech Stack, consultar `https://search.maven.org` e anotar a versão estável mais recente que respeita os mínimos de Global Constraints. Fixar todas em `<properties>` do `pom.xml` pai.
- [ ] **Step 2: `pom.xml` pai** com `<packaging>pom</packaging>`, `<maven.compiler.release>25</maven.compiler.release>`, `<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>`, `spring-boot-dependencies` importado em `<dependencyManagement>`, e a lista de `<modules>` (começa com `platform/kernel` e `app`; cada tarefa seguinte acrescenta o seu).
- [ ] **Step 3: Wrapper.** `cd backend-java && mvn -N wrapper:wrapper -Dmaven=3.9.9` (ou a 3.9.x mais recente).
- [ ] **Step 4: Teste que falha** — `ApplicationStartsIT`:

```java
/**
 * Prova que a aplicação sobe com a configuração mínima e responde à verificação de saúde.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationStartsIT {

    /** Porta aleatória escolhida pelo Spring para este teste. */
    @LocalServerPort
    int port;

    /**
     * Sobe o servidor e confere que o actuator responde 200.
     */
    @Test
    @DisplayName("a aplicação sobe e o actuator responde")
    void starts() {

        // ── 1. Chama o endpoint de saúde do actuator pela rede, como um cliente real.
        var response = RestAssured.given().port(port).get("/actuator/health");

        // ── 2. O servidor precisa estar de pé.
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
```

- [ ] **Step 5: Rodar e ver falhar.** `./mvnw -f backend-java/pom.xml verify` → FAIL (classe principal inexistente).
- [ ] **Step 6: Implementar** `RaykookieApplication` (com Javadoc) e `application.yml` com `server.port: ${PORT:10005}`, `spring.threads.virtual.enabled: true`, `management.endpoints.web.exposure.include: health,info,prometheus`. (O banco entra na Tarefa 2.2; aqui o teste usa `spring.autoconfigure.exclude` de DataSource no perfil `test`.)
- [ ] **Step 7: Rodar e ver passar.** Mesmo comando → PASS.
- [ ] **Step 8: Commit** `build(java): esqueleto Maven multi-módulo do backend Java`.

### Task 1.2: Guardiões de qualidade — estilo, arquitetura, isolamento, cobertura

**Files:**
- Create: `backend-java/config/checkstyle.xml`
- Create: `backend-java/platform/testing/pom.xml`, `.../testing/src/main/java/br/com/kntap/raykookie/testing/style/CodeStyleRules.java`
- Test: `backend-java/app/src/test/java/br/com/kntap/raykookie/quality/CodeStyleTest.java`, `ArchitectureTest.java`, `IsolationTest.java`
- Modify: `backend-java/pom.xml` (plugins `maven-checkstyle-plugin` na fase `validate` com `failOnViolation=true`; `jacoco-maven-plugin` com `check` na fase `verify`; `pitest-maven` em perfil `mutation`)

**Interfaces:**
- Produces: `CodeStyleRules.violations(Path sourceRoot): List<String>` usada por `CodeStyleTest`; regras ArchUnit reutilizáveis `ModuleRules.onlyApiIsShared(String module)`.

- [ ] **Step 1: Checkstyle** (`config/checkstyle.xml`) com: `MissingJavadocType`, `MissingJavadocMethod` (`scope=private`, `allowMissingPropertyJavadoc=false`, `minLineCount=0`), `JavadocMethod` (`validateThrows=false`), `JavadocParagraph`, `OneStatementPerLine`, `MethodLength` (`max=40`), `LineLength` (`max=120`), `AvoidNestedBlocks`, `NeedBraces`, `EmptyCatchBlock`, `IllegalType` para `double`/`float` dentro de pacotes `..domain..` de `payments`, `refunds`, `orders`, `kernel`.
- [ ] **Step 2: Teste que falha — `CodeStyleTest`** (usa JavaParser):

```java
/**
 * Garante o padrão de código pedido pelo dono do projeto: métodos longos divididos em
 * blocos lógicos, cada bloco aberto por um comentário numerado "// ── N. explicação".
 */
class CodeStyleTest {

    /**
     * Percorre todo o código de produção de backend-java e falha listando cada violação.
     */
    @Test
    @DisplayName("todo método com mais de 6 instruções tem blocos lógicos comentados")
    void logicalBlocksAreDocumented() throws IOException {

        // ── 1. Localiza a raiz de backend-java a partir deste módulo de teste.
        Path root = Path.of("..").toRealPath();

        // ── 2. Analisa os fontes de produção e coleta as violações.
        List<String> violations = CodeStyleRules.violations(root);

        // ── 3. Falha mostrando arquivo:linha de cada problema.
        assertThat(violations).isEmpty();
    }
}
```

- [ ] **Step 3: Implementar `CodeStyleRules.violations`.** Para cada `*.java` em `*/src/main/java`: para cada `MethodDeclaration`/`ConstructorDeclaration` com corpo, contar as instruções de primeiro nível; se > 6, separar as instruções em blocos pelas linhas em branco do fonte original (comparar `getBegin().line` de instruções consecutivas: diferença ≥ 2 = novo bloco); exigir ≥ 2 blocos e que cada bloco tenha um comentário de linha imediatamente acima cujo texto case com `^── (\d+)\. \S.{9,}` e cujos números sigam 1, 2, 3… sem pular. Também exigir Javadoc (`getJavadocComment().isPresent()`) em todo método/construtor/tipo. Mensagem: `"<arquivo>:<linha> <método>: <regra violada>"`.
- [ ] **Step 4: `ArchitectureTest`** (ArchUnit) com as regras:
  1. Classes em `..domain..` não dependem de `org.springframework..`, `java.sql..`, `..adapter..` nem `..application..`.
  2. Classes em `..application..` não dependem de `..adapter..`.
  3. Para cada módulo M em `modules/*`: classes fora de M só acessam `br.com.kntap.raykookie.M.api..`.
  4. Controllers (`@RestController`) ficam em `..adapter.in.web..` e todo método público mapeado tem `@Access` (complementa a Tarefa 2.5).
  5. Nenhuma classe usa `double`/`float` em campos ou parâmetros nos pacotes `..payments..`, `..refunds..`, `..orders..`, `..kernel..` (dinheiro, G12).
  6. Sem ciclos entre pacotes de primeiro nível dos módulos (`slices().matching("br.com.kntap.raykookie.(*)..").should().beFreeOfCycles()`).
  7. Testes em `..payments..`, `..refunds..`, `..orders..` não usam `Mockito.mock` de tipos `*Repository` (T04).
- [ ] **Step 5: `IsolationTest`** — percorre `backend-java/**` (exceto `target/`) e falha se algum `pom.xml`, `Dockerfile`, `application*.yml` ou `.java` contiver um caminho que resolva para fora de `backend-java/` (`../` a partir da raiz do módulo, ou `relativePath` de pai fora da pasta).
- [ ] **Step 6: JaCoCo** com regras `BUNDLE LINE COVEREDRATIO 0.85` no agregado e `0.95` nos módulos `payments`, `refunds`, `orders` (configurado nos poms desses módulos quando forem criados). Pitest no perfil `mutation`: `targetClasses br.com.kntap.raykookie.kernel.*,br.com.kntap.raykookie.payments.domain.*,br.com.kntap.raykookie.refunds.domain.*`, `mutationThreshold 80`.
- [ ] **Step 7: Rodar.** `./mvnw -f backend-java/pom.xml verify` → PASS (o código existente já segue o padrão).
- [ ] **Step 8: Prova de que os guardiões mordem.** Criar temporariamente um método sem Javadoc em `RaykookieApplication`, rodar, ver FAIL com a mensagem do `CodeStyleTest`, desfazer.
- [ ] **Step 9: Commit** `build(java): guardiões de estilo, arquitetura, isolamento e cobertura`.

### Task 1.3: Imagem Docker e CI do Java

**Files:**
- Create: `backend-java/Dockerfile`, `backend-java/.dockerignore`
- Modify: `.github/workflows/ci.yml` (novo job `backend-java`)

- [ ] **Step 1: Dockerfile** (contexto = `backend-java/`):

```dockerfile
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -q -B -DskipTests package -pl app -am

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/app/target/raykookie-app.jar /app/raykookie.jar
ENV DOWNLOADS_DIR=/app/downloads
EXPOSE 10005
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:10005/api/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/raykookie.jar"]
```

- [ ] **Step 2: Job de CI** `backend-java`: `actions/setup-java` (temurin 25), `./mvnw -B -f backend-java/pom.xml verify` (Testcontainers usa o Docker do runner), publica o relatório JaCoCo como artefato. Falha se houver teste pulado (`grep -E "Skipped: [1-9]" target/surefire-reports/*.txt`).
- [ ] **Step 3: Rodar localmente** `docker build -t raykookie-api-java-teste backend-java` → imagem criada; remover a imagem depois.
- [ ] **Step 4: Commit** `ci(java): imagem Docker e job de CI do backend Java`.

---

## Fase 2 — Plataforma (base comum a todos os módulos)

### Task 2.1: Kernel — Money, ids, erros e relógio da loja (G12)

**Files:**
- Create em `backend-java/platform/kernel/src/main/java/br/com/kntap/raykookie/kernel/`: `Money.java`, `MoneyParser.java`, `DomainException.java`, `NotFoundException.java`, `ConflictException.java`, `InvalidInputException.java`, `TooManyRequestsException.java`, `StoreClock.java`, `ids/OwnerId.java`, `ids/CustomerId.java`, `ids/ShopOrderId.java`
- Test: `MoneyTest.java`, `MoneyParsingPropertyTest.java` (jqwik), `StoreClockTest.java`
- Create: `backend-java/rules-vectors/money-parse.json`

**Interfaces:**
- Produces:
  - `record Money(long cents)` com `static Money ofCents(long)`, `static Money fromDecimal(BigDecimal)` (exige escala ≤ 2, senão `InvalidInputException`), `BigDecimal toDecimal()` (escala 2), `Money plus(Money)`, `Money minus(Money)`, `Money times(long)`, `boolean isLessThan(Money)`, `boolean isZero()`, `String toBrl()` (`"R$ 1.500,50"`).
  - `MoneyParser.parse(String text): Money` — aceita `"12,5"`, `"12.5"`, `"1.500,50"`, `"1500"`; recusa vazio, negativo, texto, mais de 2 casas.
  - `StoreClock.today(): LocalDate`, `StoreClock.monthKey(): LocalDate` (dia 1), zona `America/Sao_Paulo`, construído sobre `java.time.Clock` injetável.
  - Exceções: `DomainException(String message, int httpStatus)` e as filhas com status fixo (404, 409, 400, 429).

- [ ] **Step 1: Gerar o vetor de paridade a partir do TypeScript.** Script único (fora do build Java, roda uma vez e o JSON é versionado): para cada entrada de uma lista de 60 textos (válidos, inválidos e limites), chamar `parseLocaleNumber` de `backend/shared/text.ts` e gravar `{ "input": "...", "cents": 150050 }` ou `{ "input": "...", "error": true }` em `backend-java/rules-vectors/money-parse.json`.
- [ ] **Step 2: Testes que falham.**

```java
/**
 * Regras exatas de dinheiro: tudo em centavos, sem perda de precisão (G12, T08).
 */
class MoneyTest {

    /**
     * Soma que em ponto flutuante daria 0.30000000000000004 precisa dar exatamente 30 centavos.
     */
    @Test
    @DisplayName("0,10 + 0,20 é exatamente 0,30")
    void exactSum() {

        // ── 1. Monta os dois valores a partir de texto pt-BR.
        Money a = MoneyParser.parse("0,10");
        Money b = MoneyParser.parse("0,20");

        // ── 2. Soma e confere o centavo exato.
        assertThat(a.plus(b)).isEqualTo(Money.ofCents(30));
    }

    /**
     * Cada linha do vetor gerado pelo TypeScript tem de dar o mesmo resultado no Java (G25).
     */
    @ParameterizedTest
    @MethodSource("vectors")
    @DisplayName("paridade com o TypeScript na leitura de valores")
    void parityWithTypeScript(String input, Long cents) {

        // ── 1. Entrada inválida no TypeScript precisa ser inválida no Java.
        if (cents == null) {
            assertThatThrownBy(() -> MoneyParser.parse(input)).isInstanceOf(InvalidInputException.class);
            return;
        }

        // ── 2. Entrada válida precisa dar o mesmo número de centavos.
        assertThat(MoneyParser.parse(input).cents()).isEqualTo(cents);
    }
}
```

`MoneyParsingPropertyTest` (jqwik, 5 000 tentativas): para todo `long c` entre 0 e 10¹², `MoneyParser.parse(Money.ofCents(c).toBrl().replace("R$ ", "")).cents() == c`; e para todo texto com letras, `parse` lança `InvalidInputException`.
- [ ] **Step 3: Rodar e ver falhar** (`./mvnw -pl platform/kernel test`).
- [ ] **Step 4: Implementar** seguindo o padrão de código (Javadoc, blocos comentados).
- [ ] **Step 5: Rodar e ver passar.** Rodar também `-Pmutation` neste módulo: ≥ 80 % de mutantes mortos.
- [ ] **Step 6: Commit** `feat(kernel): Money em centavos, leitura pt-BR e relógio da loja`.

### Task 2.2: Persistência — Hikari com tempos limite, Flyway com baseline e migrations V22 (G06, G07, G29, T10)

**Files:**
- Create: `backend-java/platform/persistence/src/main/java/br/com/kntap/raykookie/persistence/PersistenceConfig.java`, `DatabaseProperties.java`, `Jdbc.java` (helpers de leitura de `Money`/datas)
- Create: `backend-java/platform/persistence/src/main/resources/db/migration/production/V1__001_init.sql` … `V21__021_card_payment.sql` (21 cópias byte a byte de `backend/migrations/`, ver Step 2) e `production/SHA256SUMS`
- Create: `.../db/migration/platform/V22__platform_tables.sql`
- Create: `backend-java/platform/persistence/src/main/java/br/com/kntap/raykookie/persistence/ProductionSchemaGuard.java`
- Create: `backend-java/platform/testing/src/main/java/br/com/kntap/raykookie/testing/PostgresTestBase.java`, `NodeStyleMigrator.java`
- Create: `backend-java/docker-compose.dev.yml`
- Modify: `.github/workflows/ci.yml` (job `migrations-parity`)
- Test: `MigrationsIT.java`, `TransactionRollbackIT.java`, `DatabaseTimeoutIT.java`

**Interfaces:**
- Produces: `PostgresTestBase` (classe abstrata com `@Testcontainers`, container `postgres:17` compartilhado, `@DynamicPropertySource` para a URL, método `protected JdbcClient jdbc()`); tabelas `rate_limits`, `job_runs`, `payment_attempts`, `webhook_deliveries`.

- [ ] **Step 1: `V22__platform_tables.sql`** (aditiva; o Node ignora estas tabelas):

```sql
-- Contadores de limite de tentativas compartilhados entre instâncias (G05, G23).
CREATE TABLE IF NOT EXISTS rate_limits (
  key           TEXT        NOT NULL,
  window_start  TIMESTAMPTZ NOT NULL,
  hits          INTEGER     NOT NULL DEFAULT 0,
  PRIMARY KEY (key, window_start)
);
CREATE INDEX IF NOT EXISTS rate_limits_window_idx ON rate_limits (window_start);

-- Última execução de cada tarefa agendada (G13).
CREATE TABLE IF NOT EXISTS job_runs (
  name         TEXT        PRIMARY KEY,
  last_run_on  DATE        NOT NULL,
  last_ok_at   TIMESTAMPTZ,
  last_error   TEXT
);

-- Tentativas de cobrança: no máximo UMA em andamento por pedido; a chave de idempotência é o próprio id (G03).
CREATE TABLE IF NOT EXISTS payment_attempts (
  id              UUID        PRIMARY KEY,
  shop_order_id   INTEGER     REFERENCES shop_orders(id),
  pay_link_id     INTEGER     REFERENCES order_pay_links(id),
  customer_id     INTEGER     REFERENCES customers(id),
  client_ip       TEXT        NOT NULL,
  method          TEXT        NOT NULL CHECK (method IN ('pix', 'cartao')),
  state           TEXT        NOT NULL CHECK (state IN ('in_flight', 'approved', 'pending', 'rejected', 'error')),
  gateway_id      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS payment_attempts_one_in_flight_order
  ON payment_attempts (shop_order_id) WHERE state = 'in_flight' AND shop_order_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS payment_attempts_one_in_flight_link
  ON payment_attempts (pay_link_id) WHERE state = 'in_flight' AND pay_link_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS payment_attempts_customer_idx ON payment_attempts (customer_id, created_at);
CREATE INDEX IF NOT EXISTS payment_attempts_ip_idx ON payment_attempts (client_ip, created_at);

-- Entregas de webhook já processadas (deduplicação por x-request-id; G18).
CREATE TABLE IF NOT EXISTS webhook_deliveries (
  request_id    TEXT        PRIMARY KEY,
  data_id       TEXT        NOT NULL,
  received_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: Migrations reais de produção dentro do projeto Java.** Copiar **byte a byte** cada arquivo `backend/migrations/0NN_nome.sql` para `backend-java/platform/persistence/src/main/resources/db/migration/production/V<NN>__<NN>_<nome>.sql` (ex.: `001_init.sql` → `V1__001_init.sql`, `021_card_payment.sql` → `V21__021_card_payment.sql`) — mesmo conteúdo, só o nome muda. Gravar `production/SHA256SUMS` com o sha256 de cada original. Job de CI `migrations-parity` (fora do build Java, para não quebrar o isolamento) recalcula os hashes de `backend/migrations/*.sql` e falha se divergirem de `SHA256SUMS` — se o Node ganhar a migration 022 antes do corte, ela tem de ser copiada também.
- [ ] **Step 3: Flyway em dois modos.**
  - **Banco novo (testes e desenvolvimento):** `locations=classpath:db/migration/production,classpath:db/migration/platform` (V22+ fica em `platform/`), `baseline-on-migrate=false`. O banco de teste nasce exatamente como o de produção nasceu: aplicando 001→021 em ordem, depois V22+.
  - **Banco existente (produção):** perfil `prod` com `locations=classpath:db/migration/platform`, `baseline-on-migrate=true`, `baseline-version=21`. Na subida, `ProductionSchemaGuard` confere que a tabela `_migrations` do Node contém exatamente os 21 nomes `001_init.sql`…`021_card_payment.sql`; se faltar algum ou houver um a mais, o servidor **não sobe** (mensagem cita o nome divergente) — impede rodar o Java contra um banco num estado que os testes não cobriram.
  - Tabela do Flyway: `flyway_schema_history` (a `_migrations` do Node não é tocada).
- [ ] **Step 4: Container de teste igual ao de produção.** `PostgresTestBase` usa `new PostgreSQLContainer<>("postgres:17.11")` com `withEnv("TZ", "America/Sao_Paulo")`, `withEnv("PGTZ", "America/Sao_Paulo")`, `withCommand("postgres", "-c", "timezone=America/Sao_Paulo")`, reutilizado por toda a suíte (`withReuse(true)` desligado no CI). Para desenvolvimento local fora dos testes, `backend-java/docker-compose.dev.yml` com o mesmo `postgres:17.11` em `127.0.0.1:54331` e `./mvnw -pl app spring-boot:run -Dspring-boot.run.profiles=local`, que aplica as mesmas migrations.
- [ ] **Step 5: Hikari** em `application.yml`: `maximum-pool-size: 10`, `connection-timeout: 3000`, `validation-timeout: 2000`, `max-lifetime: 1800000`, `connection-init-sql: "SET statement_timeout = '15s'; SET TIME ZONE 'America/Sao_Paulo'"`. `DatabaseProperties` (`@ConfigurationProperties("rayka.db")`, `@Validated`) exige `url` não vazia.
- [ ] **Step 6: Testes que falham.**
  - `MigrationsIT.productionMigrationsMatchOriginals`: o sha256 de cada `production/V*.sql` bate com `SHA256SUMS` (protege contra alguém "arrumar" uma migration copiada).
  - `MigrationsIT.nodeStyleDatabaseAcceptsBaseline`: num container `postgres:17.11` vazio, aplica os 21 arquivos **do jeito que o `backend/scripts/migrate.mjs` aplica** (classe de teste `NodeStyleMigrator`: cria `_migrations`, uma transação por arquivo, em ordem, grava o nome original `0NN_nome.sql`) — o banco fica igual ao de produção; depois roda o Flyway no modo produção (baseline 21) ⇒ aplica só V22+, `flyway.validate()` não lança e o `ProductionSchemaGuard` aceita.
  - `MigrationsIT.bothPathsProduceSameSchema`: compara `information_schema.columns`, `pg_indexes` e `pg_constraint` (nome, tabela, definição) entre o banco do modo "novo" e o do modo "produção" ⇒ idênticos.
  - `MigrationsIT.guardRejectsUnknownState`: `_migrations` com só 20 arquivos ⇒ o servidor não sobe e a mensagem cita `021_card_payment.sql`.
  - `MigrationsIT.migrationsAreAdditiveOnly`: lê todos os `V*.sql` com número ≥ 22 e falha se contiverem `DROP `, `RENAME `, `ALTER COLUMN` ou `TYPE ` (G29, política expandir/contrair).
  - `TransactionRollbackIT`: um método `@Transactional` que insere numa tabela e lança exceção ⇒ nada gravado; a conexão seguinte do pool funciona (G07).
  - `DatabaseTimeoutIT`: `SELECT pg_sleep(20)` lança em ≤ 16 s (statement_timeout); com o container pausado (`container.getDockerClient().pauseContainerCmd(...)`), obter conexão falha em ≤ 4 s (G06, T09).
- [ ] **Step 7: Rodar e ver falhar; implementar; rodar e ver passar.**
- [ ] **Step 8: Commit** `feat(persistence): migrations reais de produção, Flyway com baseline 21 e Hikari com tempos limite`.

### Task 2.3: Web — erros sempre em JSON, limites de corpo e requestId (G08, G20)

**Files:**
- Create em `backend-java/platform/web/src/main/java/br/com/kntap/raykookie/web/`: `GlobalErrorHandler.java`, `ErrorBody.java`, `RequestSizeLimitFilter.java`, `RequestIdFilter.java`, `WebProperties.java`
- Test: `GlobalErrorHandlerIT.java`, `RequestSizeLimitIT.java`

**Interfaces:**
- Produces: `record ErrorBody(String error)`; todo erro sai como `{"error": "<mensagem pt-BR>"}`. Mapeamento: `DomainException` → seu status; `MethodArgumentNotValidException`/`HttpMessageNotReadableException` → 400 `"Dados inválidos."` (ou a mensagem do primeiro campo); `NoResourceFoundException` → 404 `"Não encontrado."`; `HttpRequestMethodNotSupportedException` → 405 `"Método não permitido."`; `MaxUploadSizeExceededException` e o filtro → 413 `"O envio é grande demais."`; `DataIntegrityViolationException` por código SQL `22003` → 400 `"Algum número está grande demais."`, `23505` → 409 `"Já existe um registro com esse nome."`, `23503` → 409 `"Este item está em uso e não pode ser removido."`, `23514` → 400 `"Algum valor está fora do permitido."` (mesmas mensagens de `backend/lib/api.ts:17-24`); `CannotGetJdbcConnectionException` → 503 `"Serviço indisponível. Tente de novo em instantes."`; resto → 500 `"Algo deu errado no servidor. Tente de novo."` com log `ERROR` contendo o `requestId`.
- Produces: `RequestSizeLimitFilter` com limites por prefixo de rota configuráveis: padrão 1 MB (JSON); `PUT /api/recipes/*/photo` 2 MB; `POST /api/cliente/pedidos/*/devolucao`, `POST /api/pagar/*/devolucao`, `POST /api/cliente/feedback` 12 MB; `POST /api/nota/ler` 3 MB.

- [ ] **Step 1: Testes que falham.** `RequestSizeLimitIT`: envia `Content-Length: 52428800` para `PUT /api/recipes/1/photo` sem corpo completo e espera 413 em ≤ 1 s com `{"error":"O envio é grande demais."}`; envia corpo *chunked* de 3 MB (sem Content-Length) e espera 413 (o filtro conta bytes ao ler). `GlobalErrorHandlerIT`: rota inexistente → 404 JSON; `DELETE /api/menu` → 405 JSON; uma rota de teste que lança `RuntimeException` → 500 JSON sem stack trace no corpo.
- [ ] **Step 2: Implementar** (filtro antes de qualquer leitura: rejeita pelo `Content-Length` declarado; se ausente, embrulha o `InputStream` num contador que lança ao passar do limite). `spring.servlet.multipart.max-file-size=2MB`, `max-request-size=12MB`, `server.tomcat.max-swallow-size=1MB`.
- [ ] **Step 3: Rodar e ver passar.**
- [ ] **Step 4: Commit** `feat(web): erros sempre em JSON e limite de tamanho antes de ler o corpo`.

### Task 2.4: IP real do cliente e limites de tentativas no Postgres (G05, G23)

**Files:**
- Create em `backend-java/platform/web/.../web/`: `ClientIpResolver.java`, `TrustedProxies.java`
- Create em `backend-java/platform/ratelimit/src/main/java/br/com/kntap/raykookie/ratelimit/`: `RateLimiter.java`, `RateLimit.java`, `JdbcRateLimiter.java`
- Test: `ClientIpResolverTest.java`, `JdbcRateLimiterIT.java`

**Interfaces:**
- Produces:
  - `ClientIpResolver.resolve(String remoteAddr, String xForwardedFor): String` — percorre o `X-Forwarded-For` **da direita para a esquerda**, pulando IPs dentro de `TRUSTED_PROXIES` (CIDRs, padrão `127.0.0.1/32,::1/128`); o primeiro IP não confiável é o cliente. Se `remoteAddr` não for confiável, o cabeçalho é ignorado e o cliente é `remoteAddr`.
  - `record RateLimit(String name, int maxHits, Duration window)`.
  - `RateLimiter.hit(RateLimit limit, String subject): boolean` (true = dentro do limite; registra a tentativa), `RateLimiter.peek(RateLimit, String): boolean` (consulta sem registrar), `RateLimiter.reset(RateLimit, String)`, `RateLimiter.purgeOlderThan(Instant): int`.
  - Limites nomeados (constantes em `RateLimits`): `OWNER_LOGIN(8, 10 min)` por `usuario|ip`; `CUSTOMER_LOGIN(8, 10 min)` por `email|ip`; `CUSTOMER_SIGNUP(5, 1 h)` por ip; `ORDER_CREATE(10, 1 h)` por cliente; `CARD_FAIL_CUSTOMER(5, 24 h)` por cliente; `CARD_FAIL_IP(10, 24 h)` por ip; `PIX_CREATE(20, 1 h)` por cliente.

- [ ] **Step 0: Medir os cabeçalhos reais antes de decidir a regra.** No ensaio (ou com um endpoint temporário de diagnóstico no Node, aprovado pelo dono), registrar `remoteAddr`, `X-Forwarded-For`, `X-Real-IP` e `X-Vercel-Forwarded-For` de três origens: app Android direto na API, navegador pelo site (rewrite da Vercel em `frontend/vercel.json`) e chamada do servidor da Vercel (`BACKEND_URL`). Se o tráfego do site chegar com um IP da Vercel como último salto não confiável (todos os usuários do site dividiriam o mesmo IP): (a) limites por IP passam a usar o IP informado pela Vercel **somente** quando a requisição trouxer o cabeçalho `x-rayka-edge` com um segredo compartilhado (`EDGE_SHARED_SECRET`), acrescentado por um middleware do `frontend/` no corte (Tarefa 7.3); (b) sem esse cabeçalho, o tráfego da Vercel cai em limites por conta/e-mail, nunca num limite global por IP. Registrar a medição e a decisão em `backend-java/docs/ip-do-cliente.md`.
- [ ] **Step 1: Testes que falham.**

```java
/**
 * O IP usado nos limites de tentativa não pode ser escolhido pelo cliente (G05).
 */
class ClientIpResolverTest {

    /** Resolve IPs com o proxy local (rproxy) como único proxy confiável. */
    private final ClientIpResolver resolver = new ClientIpResolver(TrustedProxies.parse("127.0.0.1/32,::1/128"));

    /**
     * Cliente que forja "1.1.1.1" no cabeçalho: o proxy acrescenta o IP real à direita,
     * e é esse que vale.
     */
    @Test
    @DisplayName("X-Forwarded-For forjado não muda o IP do cliente")
    void forgedHeaderIsIgnored() {

        // ── 1. Requisição chegando pelo proxy local com o cabeçalho forjado + IP real.
        String ip = resolver.resolve("127.0.0.1", "1.1.1.1, 203.0.113.7");

        // ── 2. O IP real (último não confiável) é o usado.
        assertThat(ip).isEqualTo("203.0.113.7");
    }

    /**
     * Requisição que NÃO veio do proxy confiável: o cabeçalho inteiro é ignorado.
     */
    @Test
    @DisplayName("cabeçalho ignorado quando a conexão não vem de proxy confiável")
    void untrustedPeer() {

        // ── 1. Conexão direta de um IP externo trazendo cabeçalho.
        String ip = resolver.resolve("198.51.100.9", "10.0.0.1");

        // ── 2. Vale o IP da conexão.
        assertThat(ip).isEqualTo("198.51.100.9");
    }
}
```

`JdbcRateLimiterIT` (Postgres real): 8 `hit` passam e o 9º retorna `false`; após avançar o relógio injetado 10 min, volta a passar; 2 instâncias de `JdbcRateLimiter` sobre o mesmo banco somam as tentativas (G23); 100 threads concorrentes fazendo `hit` com limite 50 ⇒ exatamente 50 `true` (`INSERT … ON CONFLICT (key, window_start) DO UPDATE SET hits = rate_limits.hits + 1 RETURNING hits`); `purgeOlderThan` apaga janelas antigas.
- [ ] **Step 2: Implementar; rodar e ver passar.**
- [ ] **Step 3: Commit** `feat(platform): IP real atrás de proxy confiável e limites de tentativa no Postgres`.

### Task 2.5: Sessões, controle de acesso fail-closed e inventário de rotas (G01, G21, G24, T03)

**Files:**
- Create em `backend-java/platform/security/src/main/java/br/com/kntap/raykookie/security/`: `Access.java` (anotação), `AccessLevel.java`, `AccessInterceptor.java`, `SessionTokens.java`, `CurrentActor.java`, `ActorResolver.java` (porta), `ShopOwner.java`
- Test: `SessionTokensTest.java`, `AccessInterceptorIT.java`; `backend-java/app/src/test/java/.../quality/AccessInventoryTest.java`

**Interfaces:**
- Produces:
  - `enum AccessLevel { PUBLIC, OWNER, CUSTOMER, OWNER_OR_PUBLIC, PAY_LINK_TOKEN, SIGNED_WEBHOOK }`.
  - `@Access(AccessLevel value)` em todo método de controller.
  - `SessionTokens.newToken(): String` (32 bytes aleatórios em base64url sem padding), `SessionTokens.hash(String token): String` (sha256 hex minúsculo), `SessionTokens.fromRequest(HttpServletRequest, String cookieName): Optional<String>` (prioriza `Authorization: Bearer <token>`, depois o cookie).
  - `ActorResolver` (porta implementada por `identity` e `customers`): `Optional<OwnerId> owner(String tokenHash)`, `Optional<CustomerId> customer(String tokenHash)`.
  - `CurrentActor` (bean de escopo de requisição): `OwnerId requireOwner()`, `CustomerId requireCustomer()`, `Optional<OwnerId> ownerIfAny()`.
  - `ShopOwner.id(): OwnerId` — resolvido na subida a partir de `SHOP_OWNER`; o servidor não sobe se o usuário não existir (G24).
  - Regra do interceptor: método sem `@Access` ⇒ 500 e log `ERROR` (fail-closed); `OWNER` exige sessão de dona **e** `id == ShopOwner.id()` (senão 401 `"Sessão expirada. Entre novamente."`, mesma mensagem de `backend/lib/api.ts:15`); `CUSTOMER` exige sessão de cliente (mensagem idêntica à de `backend/lib/customer-api.ts`).

- [ ] **Step 1: Teste de compatibilidade de token (vetor do Node).**

```java
/**
 * Sessões criadas pelo Node têm de continuar válidas no Java: mesmo hash do token.
 */
class SessionTokensTest {

    /**
     * Vetor gerado no Node: crypto.createHash('sha256').update('token-abc').digest('hex').
     */
    @Test
    @DisplayName("hash do token idêntico ao do Node")
    void sameHashAsNode() {

        // ── 1. Calcula o hash do token conhecido.
        String hash = SessionTokens.hash("token-abc");

        // ── 2. Compara com o valor produzido pelo Node.
        assertThat(hash).isEqualTo("096157b339cf419dbc0c8def49fc2be924dc130fbacdfa7b879bee0a3078cec4");
    }
}
```

- [ ] **Step 2: `AccessInventoryTest`** — sobe o contexto, lê `RequestMappingHandlerMapping.getHandlerMethods()` e falha listando todo handler sem `@Access`; também falha se o conjunto `(método HTTP, caminho)` não for exatamente o da lista da Fase 4–6 (arquivo `backend-java/app/src/test/resources/expected-routes.txt`, uma linha `MÉTODO caminho ACESSO` por rota — 111 linhas, tiradas do inventário do Node, ver Apêndice A). Rota a mais ou a menos = falha.
- [ ] **Step 3: `AccessInterceptorIT`** com controllers de teste: sem `@Access` → 500; `OWNER` sem sessão → 401; `OWNER` com sessão de usuário que não é o `SHOP_OWNER` → 401; `OWNER` com cookie `rayka_session` válido → 200; com `Authorization: Bearer` válido → 200; sessão vencida → 401; `CUSTOMER` com token de dona → 401 (tokens não se misturam).
- [ ] **Step 4: Implementar; rodar e ver passar.**
- [ ] **Step 5: Commit** `feat(security): sessões compatíveis com o Node e acesso fail-closed`.

### Task 2.6: Configuração validada na subida (G14, G15)

**Files:**
- Create: `backend-java/app/src/main/java/br/com/kntap/raykookie/config/RaykookieProperties.java`, `backend-java/.env.example`
- Test: `ConfigurationValidationTest.java`

**Interfaces:**
- Produces: `RaykookieProperties` (`@ConfigurationProperties("rayka")`, `@Validated`) com: `siteUrl` (URL https em produção), `cookieSecure` (`boolean`, só aceita `true`/`false`), `shopOwner` (não vazio), `registrationEnabled` (padrão `false`), `downloadSecret` (≥ 32 caracteres, obrigatório), `downloadsDir`, `jobsEnabled` (padrão `true`), `trustedProxies`, `mp.accessToken`, `mp.publicKey`, `mp.webhookSecret`, `mp.apiBase` (padrão `https://api.mercadopago.com`), `mp.webhookMaxSkew` (padrão 10 min), `pixExpiresMinutes` (31–43200, padrão 60), `payLinkPayerEmail`, `google.clientId`, `google.clientSecret`, `firebaseServiceAccountPath`, `alertWebhookUrl`. Mapeamento das variáveis atuais: `SITE_URL`, `COOKIE_SECURE`, `SHOP_OWNER`, `REGISTRATION_ENABLED`, `DOWNLOAD_SECRET`, `DOWNLOADS_DIR`, `TRUSTED_PROXIES`, `MP_ACCESS_TOKEN`, `MP_PUBLIC_KEY`, `MP_WEBHOOK_SECRET`, `MP_API_BASE`, `PIX_EXPIRES_MINUTES`, `PAY_LINK_PAYER_EMAIL`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `FIREBASE_SERVICE_ACCOUNT_PATH`, `ALERT_WEBHOOK_URL`, `DATABASE_URL` (convertida de `postgres://user:pass@host:port/db` para JDBC por `DatabaseUrlParser`). `JOBS_SECRET` deixa de existir (G13).

- Produces também (G31): `paymentsCardEnabled` (`PAYMENTS_CARD_ENABLED`, padrão `false`); quando `true`, `mp.publicKey` e `mp.accessToken` são obrigatórios; `MercadoPagoEnvironmentCheck` (roda na subida, fora dos testes unitários) confere: (1) o token é válido (`GET {mp.apiBase}/users/me` responde 200); (2) a chave pública é válida (`GET {mp.apiBase}/v1/payment_methods/installments?bin=503143&amount=10&public_key=…` responde 200); (3) os dois são do mesmo ambiente **se** a Tarefa 0.3 encontrar uma forma documentada de saber o ambiente/conta de cada um (registrada no relatório; se não houver, o item 3 fica de fora e essa combinação é coberta só pela suíte ao vivo). Falha em qualquer item ⇒ o servidor sobe com o cartão **desligado**, registra `OpsAlert.integrationDown("mercadopago-cartao", …)` e `/api/payment-config` não anuncia cartão (o site/app escondem a opção em vez de falhar no pagamento).

- [ ] **Step 1: Testes que falham** (`ApplicationContextRunner`): `COOKIE_SECURE="true   # comentário"` ⇒ contexto não sobe e a mensagem cita `COOKIE_SECURE`; `PAYMENTS_CARD_ENABLED=true` sem `MP_PUBLIC_KEY` ⇒ não sobe e a mensagem cita `MP_PUBLIC_KEY`; chave pública de um ambiente e token de outro (WireMock respondendo contas diferentes) ⇒ sobe com cartão desligado e alerta disparado; sem `DOWNLOAD_SECRET` ⇒ não sobe; `DOWNLOAD_SECRET` com 10 caracteres ⇒ não sobe; `DATABASE_URL=postgres://u:p@h:5432/db` ⇒ JDBC `jdbc:postgresql://h:5432/db` com usuário `u` e senha `p`.
- [ ] **Step 2: `.env.example`** com uma variável por linha, comentários em linhas próprias, nenhum comentário depois do valor.
- [ ] **Step 3: Implementar; rodar e ver passar.**
- [ ] **Step 4: Commit** `feat(config): configuração validada na subida`.

### Task 2.7: Tarefas agendadas com trava e recuperação (G13, G16, G23)

**Files:**
- Create em `backend-java/platform/scheduling/src/main/java/br/com/kntap/raykookie/scheduling/`: `JobRunner.java`, `ScheduledJob.java` (interface), `JobRunsRepository.java`
- Test: `JobRunnerIT.java`

**Interfaces:**
- Produces:
  - `interface ScheduledJob { String name(); JobFrequency frequency(); void run(); }` com `enum JobFrequency { EVERY_5_MIN, EVERY_6_HOURS, DAILY_AT_4AM }`.
  - `JobRunner.runDue()` — chamado por `@Scheduled(cron = "0 */5 * * * *", zone = "America/Sao_Paulo")`; para cada job registrado: decide se está devido (EVERY_5_MIN sempre; EVERY_6_HOURS se a última execução OK tem ≥ 6 h; DAILY_AT_4AM se `last_run_on < hoje` e hora local ≥ 4), tenta `pg_try_advisory_xact_lock(hashtext('job:' || name))` numa transação própria, roda, grava `job_runs`. Falha ⇒ `OpsAlert.jobFailed(name, erro)`; não impede os outros jobs.
- Jobs que os módulos registram (cada um na sua tarefa): `refunds.retry-due` (5 min), `refunds.reconcile-pending` (6 h), `payments.reconcile-links` (6 h), `payments.expire-stale-attempts` (5 min: `in_flight` com mais de 2 min ⇒ consulta o gateway e fecha), `purchasing.refresh-linked-prices` (diário 4h), `refunds.cleanup-old-photos` (diário 4h), `identity.purge-expired-sessions` (diário 4h), `customers.purge-expired-sessions` (diário 4h), `platform.purge-rate-limits` (diário 4h).

- [ ] **Step 1: Testes que falham** (relógio injetável): job diário não rodou ontem porque o servidor estava fora ⇒ roda na primeira chamada depois das 4h de hoje e não roda de novo no mesmo dia; duas instâncias de `JobRunner` chamando `runDue()` ao mesmo tempo (2 threads + latch) ⇒ o job roda exatamente 1 vez; job que lança ⇒ `job_runs.last_error` preenchido, alerta disparado, próximo job da lista roda.
- [ ] **Step 2: Implementar; rodar e ver passar.**
- [ ] **Step 3: Commit** `feat(scheduling): tarefas agendadas com trava no banco e recuperação de execuções perdidas`.

### Task 2.8: Observabilidade e alertas (G28)

**Files:**
- Create em `backend-java/platform/observability/src/main/java/br/com/kntap/raykookie/observability/`: `OpsAlert.java`, `AlertWebhookClient.java`
- Create: `backend-java/app/src/main/resources/logback-spring.xml`
- Test: `OpsAlertTest.java`

**Interfaces:**
- Produces: `OpsAlert.paymentProblem(String what, Map<String,Object> context)`, `OpsAlert.refundFailed(...)`, `OpsAlert.jobFailed(String job, Throwable error)`, `OpsAlert.integrationDown(String integration, Throwable error)`. Cada chamada: log `ERROR` com marcador `ALERT` e os campos em JSON; incrementa `rayka_alerts_total{kind=...}`; se `ALERT_WEBHOOK_URL` existir, faz POST `{"kind","message","context","requestId"}` com tempo limite de 3 s, sem nunca lançar.

- [ ] **Step 1: Testes que falham** (WireMock como destino do webhook): alerta chega com o `requestId`; webhook fora do ar não lança e conta `rayka_alert_webhook_failures_total`.
- [ ] **Step 2: Logback JSON** (logstash-logback-encoder) com `requestId` do MDC (`RequestIdFilter`, Tarefa 2.3); em perfil `local`, log de texto.
- [ ] **Step 3: Implementar; rodar e ver passar.**
- [ ] **Step 4: Commit** `feat(observability): logs JSON, métricas e alertas operacionais`.

---

## Fase 3 — Suíte de contrato caixa-preta (rede de segurança da migração)

### Task 3.0: Harness da suíte de contrato (T02, G26)

**Files:**
- Create: `backend-java/contract-tests/pom.xml`, `.../contract/ContractTarget.java`, `ContractSeed.java`, `MercadoPagoFake.java`, `ChangedFromNode.java`, `ContractTestBase.java`
- Create: `backend-java/contract-tests/README.md` (como rodar contra Node e contra Java)

**Interfaces:**
- Produces:
  - Variáveis: `CONTRACT_BASE_URL` (servidor sob teste), `CONTRACT_DB_URL` (banco **descartável** usado por esse servidor; o harness recusa rodar se o nome do banco não terminar em `_test`), `CONTRACT_TARGET` (`node` ou `java`), `CONTRACT_MP_FAKE_PORT` (o servidor sob teste precisa ter `MP_API_BASE=http://127.0.0.1:<porta>`).
  - `@ChangedFromNode("G0x")`: teste só roda com `CONTRACT_TARGET=java` (comportamento mudado de propósito).
  - `ContractSeed` cria dados por SQL: `owner()` (usuário = `SHOP_OWNER` do servidor sob teste, senha conhecida), `customer(email)`, `recipeOnMenu(name, priceCents)`, `shopOrder(...)`, `payLink(...)`; devolve tokens de sessão já gravados (`sha256`) para usar como Bearer.
  - `MercadoPagoFake` (WireMock) servindo as **gravações reais do sandbox** feitas na Tarefa 3.1 (`src/test/resources/mp-recordings/`): `POST /v1/card_tokens`, `GET /v1/payment_methods/installments`, `POST /v1/payments` (Pix com `qr_code`; cartão com cada nome mágico do titular → a resposta gravada daquele nome), `GET /v1/payments/{id}`, `POST /v1/payments/{id}/refunds`, `GET /v1/payments/{id}/refunds`, `PUT /v1/payments/{id}`, `GET /users/me`. A escolha da resposta gravada é feita pelo `cardholder.name` do token (o fake guarda token → nome ao "tokenizar"), exatamente como o sandbox faz. Enquanto a 3.1 não estiver feita, a suíte de contrato roda só as rotas sem Mercado Pago. Também `signWebhook(dataId, requestId, ts)` com o mesmo HMAC do Node.
- Regra: todo teste da suíte valida status, `content-type`, corpo JSON (campos e tipos via JSON Schema em `src/test/resources/schemas/<rota>.json`) e mensagem de erro em português.

- [ ] **Step 1: Escrever o harness e um primeiro teste** `HealthContractTest`: `GET /api/health` → 200 `{"ok":true}` com banco no ar.
- [ ] **Step 2: Rodar contra o Node** (servidor buildado em `backend/`, banco descartável `rayka_contract_test`, `MP_API_BASE` apontando para o fake): `CONTRACT_TARGET=node ./mvnw -pl contract-tests verify` → PASS.
- [ ] **Step 3: Commit** `test(contract): harness da suíte de contrato por HTTP`.

### Task 3.1: Gravações reais do sandbox do Mercado Pago (base do fake usado no CI)

**Depende de:** Tarefa 0.3 (credenciais do dono em `~/.raykookie/mercadopago.env`).

**Files:**
- Create: `backend-java/contract-tests/src/test/java/.../contract/mp/MercadoPagoRecorder.java`
- Create: `backend-java/contract-tests/src/test/resources/mp-recordings/**` (mapeamentos WireMock gerados)
- Create: `backend-java/contract-tests/src/test/resources/mp-recordings/README.md` (data da gravação, conta de teste usada — só o id —, o que foi mascarado)

- [ ] **Step 1: Gravador.** WireMock em modo *record* como proxy para `https://api.mercadopago.com`. Roteiro executado pelo gravador, com as credenciais de teste: tokenizar os 4 cartões de teste; `installments` para cada bin; pagar com cartão um pedido de R$ 10,00 para **cada nome mágico** (APRO, OTHE, CONT, CALL, FUND, SECU, EXPI, FORM), enviando `issuer_id`, `X-Idempotency-Key` UUID e o e-mail do comprador de teste; repetir o APRO com a **mesma** chave de idempotência (grava o comportamento real de idempotência); criar um Pix de R$ 10,00; consultar cada pagamento; devolver o APRO inteiro e consultar as devoluções; devolver um valor parcial; cancelar o Pix pendente; `GET /users/me`.
- [ ] **Step 2: Mascarar.** Antes de salvar: trocar e-mails, nomes, CPF, ids de conta e `Authorization` por valores fixos de teste; manter status, `status_detail`, formatos de id, códigos de erro e cabeçalhos de resposta **exatamente** como vieram.
- [ ] **Step 3: Teste de sanidade das gravações** `RecordingsCoverageTest`: existe uma resposta gravada para cada nome mágico e o `status`/`status_detail` de cada uma bate com a tabela de "Fidelidade dos sistemas externos"; se o Mercado Pago tiver respondido diferente do documentado, o teste falha e a diferença é registrada em `docs/mercadopago-cartao-diagnostico.md` antes de seguir.
- [ ] **Step 4: Commit** `test(contract): gravações reais do sandbox do Mercado Pago`.
- Regravação: rodar o gravador de novo sempre que a suíte ao vivo (Tarefa 5.2, parte B) detectar resposta diferente da gravada.

**Regra para todas as tarefas de módulo (Fases 4–6):** cada tarefa de módulo **começa** escrevendo os testes de contrato das suas rotas em `contract-tests/src/test/java/.../<modulo>/`, roda contra o Node até passarem (isso grava o comportamento atual), e só então implementa o módulo Java até a mesma suíte passar com `CONTRACT_TARGET=java`. Os testes `@ChangedFromNode` são escritos junto e falham contra o Node por definição (não rodam com `CONTRACT_TARGET=node`).

---

## Fases 4–6 — Módulos de negócio

**Forma de cada tarefa de módulo** (vale para 4.1 a 6.4; os passos se repetem em todas):

- [ ] Ler por inteiro os arquivos Node listados em "Referência Node".
- [ ] Escrever os testes de contrato das rotas do módulo (todas as linhas da tabela "Rotas"): caminho feliz, erro de validação, sem sessão (401), recurso de outra conta (404), e os casos listados em "Testes obrigatórios". Rodar contra o Node até passar.
- [ ] Escrever os testes de domínio (puros) e de caso de uso (Postgres real) listados em "Testes obrigatórios". Rodar e ver falhar.
- [ ] Implementar `domain` → `application` → `adapter.out.persistence` → `adapter.in.web`, no padrão de código obrigatório.
- [ ] Rodar o módulo (`./mvnw -pl modules/<m> -am verify`), a suíte de contrato com `CONTRACT_TARGET=java`, e o `AccessInventoryTest`.
- [ ] Commit `feat(<m>): ...`.

### Task 4.1: Módulo `identity` — conta da dona e sessões (G01, G05, G16, G24)

**Referência Node:** `backend/lib/auth.ts`, `backend/lib/defaults.ts`, `backend/app/api/auth/{login,logout,register}/route.ts`, `backend/app/api/me/route.ts`.

**Rotas:**

| Método | Caminho | Acesso |
|---|---|---|
| POST | `/api/auth/login` | PUBLIC |
| POST | `/api/auth/logout` | PUBLIC |
| POST | `/api/auth/register` | PUBLIC (desligado por padrão) |
| GET | `/api/me` | OWNER |

**Interfaces:**
- Produces: `identity.api.IdentityApi` com `Optional<OwnerId> ownerBySessionHash(String hash)`; implementação de `ActorResolver.owner`; `ScryptPasswordHasher` (`String hash(String password)`, `boolean verify(String password, String stored)`), também usado por `customers`; evento `OwnerRegistered(OwnerId)` (o `catalog` escuta e cria `settings` e as categorias padrão de `backend/lib/defaults.ts`).
- Consumes: `RateLimiter` + `RateLimits.OWNER_LOGIN`, `ClientIpResolver`, `SessionTokens`, `ShopOwner`.

**Regras a preservar:** usuário `^[\p{L}\p{N}_.-]{3,32}$`; senha ≥ 8; busca por `lower(username)`; verificação de senha mesmo sem usuário (tempo constante, hash fictício `scrypt$00$00`); cookie `rayka_session` `HttpOnly; SameSite=Lax; Path=/; Secure` se `COOKIE_SECURE`; validade 30 dias; corpo `{"ok":true,"token":"..."}` só com `x-client: mobile`; logout apaga a sessão e o cookie.

**Mudanças (marcadas `@ChangedFromNode`):** G01 — `POST /api/auth/register` responde 404 `{"error":"Não encontrado."}` salvo se `REGISTRATION_ENABLED=true` **e** `users` vazia; G05 — limite chaveado por `usuario|ip-real`; G24 — login de usuário que não é o `SHOP_OWNER` responde 401 `"Usuário ou senha incorretos."`; senha > 200 caracteres ⇒ 400.

**Testes obrigatórios:**
- `ScryptPasswordHasherTest.verifiesNodeHash`: `verify("senha-de-teste-123", "scrypt$00112233445566778899aabbccddeeff$216992f0b9009b3af7a1ae842f0b167c45e4c876e89b9ebea0aae61d62c690a42dea16df88ba4d801cf641c80e33edeacb7d65df1e739162cb307c6536e905ff")` é `true`; senha errada é `false`; `hash()` produz formato `scrypt$<32 hex>$<128 hex>` (BouncyCastle `SCrypt.generate(utf8, salt, 16384, 8, 1, 64)`).
- `NodeSessionCompatibilityIT`: sessão inserida como o Node faria (`INSERT INTO sessions(user_id, token_hash, expires_at)`) é aceita via cookie e via Bearer.
- Contrato: login certo (cookie presente, `Secure` conforme config), login errado 401, 9ª tentativa 429 `"Muitas tentativas. Espere alguns minutos e tente de novo."`, XFF forjado não reinicia a contagem (T07), logout invalida o token, `/api/me` sem sessão 401.
- Job `identity.purge-expired-sessions` apaga só sessões vencidas.

### Task 4.2: Módulo `customers` — conta do cliente, Google, LGPD e pedidos de privacidade (G05, G16, G19)

**Referência Node:** `backend/lib/customer-auth.ts`, `backend/lib/customer-api.ts`, `backend/lib/google.ts`, `backend/lib/customer-privacy.ts`, `backend/shared/lgpd.ts`, rotas `backend/app/api/cliente/{cadastro,login,logout,google,google/callback,google/token,me,me/dados,me/exportar,me/google,me/privacidade}`, `backend/app/api/privacy-requests/**`.

**Rotas:**

| Método | Caminho | Acesso |
|---|---|---|
| POST | `/api/cliente/cadastro` | PUBLIC |
| POST | `/api/cliente/login` | PUBLIC |
| POST | `/api/cliente/logout` | PUBLIC |
| GET | `/api/cliente/google` | PUBLIC |
| GET | `/api/cliente/google/callback` | PUBLIC |
| POST | `/api/cliente/google/token` | PUBLIC |
| GET, PATCH, DELETE | `/api/cliente/me` | CUSTOMER |
| GET | `/api/cliente/me/dados` | CUSTOMER |
| GET | `/api/cliente/me/exportar` | CUSTOMER |
| DELETE | `/api/cliente/me/google` | CUSTOMER |
| POST | `/api/cliente/me/privacidade` | CUSTOMER |
| GET | `/api/privacy-requests` | OWNER |
| POST | `/api/privacy-requests/{id}` | OWNER |

**Interfaces:**
- Produces: `customers.api.CustomersApi` (`Optional<CustomerSummary> byId(CustomerId)`, `CustomerContact contactFor(CustomerId)`); implementação de `ActorResolver.customer`; eventos `PrivacyRequestCreated` (o `notifications` escuta).
- Consumes: `ScryptPasswordHasher` (identity.api), `RateLimits.CUSTOMER_LOGIN`, `RateLimits.CUSTOMER_SIGNUP`.

**Regras a preservar:** cookie `rk_customer`; e-mail minúsculo; nome ≤ 80; senha 8–200; cliente "convidado" (`guest`) não faz login; fluxo OAuth web com cookie `rk_oauth` (`state` aleatório de 24 bytes, `Path=/api/cliente/google`, 10 min); vínculo por `google_sub`, depois por e-mail, senão cria; exclusão/anonimização e exportação conforme `customer-privacy.ts`.

**Mudanças:** G19 — `POST /api/cliente/login` com conta só-Google responde 401 `"E-mail ou senha incorretos. Se você criou a conta com o Google, use “Continuar com o Google”."` (mesma mensagem para senha errada e e-mail inexistente). Verificação do `id_token` passa a ser **local** (`GoogleIdTokenVerifier` com `audience = GOOGLE_CLIENT_ID`, emissores `accounts.google.com` e `https://accounts.google.com`, exigindo `email_verified`), em vez do endpoint `tokeninfo`.

**Testes obrigatórios:** contrato completo das rotas; cadastro duplicado 409; 6º cadastro do mesmo IP em 1 h ⇒ 429; `id_token` com `aud` errado ⇒ 401 `"Identificação do Google emitida para outro aplicativo."` (token assinado por chave de teste servida pelo WireMock como JWKS); callback com `state` diferente do cookie ⇒ redireciona com erro `google-invalido`; exportação contém todos os pedidos do cliente e nenhum de outro cliente; exclusão apaga as sessões do cliente.

### Task 4.3: Módulo `notifications` — push e aparelhos (G17)

**Referência Node:** `backend/lib/push.ts`, `backend/shared/push.ts`, `backend/app/api/devices/route.ts`, `backend/app/api/cliente/devices/route.ts`.

**Rotas:** `POST, DELETE /api/devices` (OWNER); `POST, DELETE /api/cliente/devices` (CUSTOMER).

**Interfaces:**
- Produces: `notifications.api.Notifier` com os mesmos avisos de `push.ts` (`newOrder`, `paid`, `cancelled`, `privacyRequest`, `ownerRefund`, `priceAlert`, `customerPaid`, `customerStatus`, `customerStoreCancel`, `customerRefund`). Os outros módulos **não chamam** o `Notifier` diretamente: publicam eventos de domínio, e o `notifications` os escuta com `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` (substitui o `void notify(...)` do Node; o aviso só sai se a transação comitou).
- Porta `PushSender` com adaptador **FCM HTTP v1 por REST** (`POST {fcm.baseUrl}/v1/projects/{projectId}/messages:send`, token OAuth obtido de `GoogleCredentials.fromStream(FIREBASE_SERVICE_ACCOUNT_PATH)` com escopo `https://www.googleapis.com/auth/firebase.messaging`; `fcm.baseUrl` padrão `https://fcm.googleapis.com`, trocado pelo WireMock nos testes). Inicialização preguiçosa; se falhar, tenta de novo depois de 10 min (G17). Os stubs do WireMock usam os **corpos de erro reais** do FCM v1 (`{"error":{"code":404,"status":"NOT_FOUND","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}` ⇒ token morto; `UNAVAILABLE`/503 ⇒ nova tentativa com espera; `INVALID_ARGUMENT` ⇒ registra e não repete), conferidos contra a documentação do FCM v1 na execução da tarefa.
- Suíte ao vivo (`-Psandbox`): envia uma mensagem real para `FCM_TEST_DEVICE_TOKEN` (aparelho de teste do dono) e confere `name` na resposta.

**Testes obrigatórios:** textos de cada mensagem idênticos ao Node (vetores copiados de `backend/tests/push.test.ts`); token morto (`messaging/registration-token-not-registered`) apagado; Firebase indisponível na 1ª tentativa e disponível 10 min depois ⇒ 2º aviso sai (relógio injetável); evento publicado numa transação que faz rollback ⇒ nenhum push.

### Task 4.4: Módulo `catalog` — ingredientes, receitas, fotos, categorias, custos, ajustes e alertas de preço (G08, G12, G25, G27)

**Referência Node:** `backend/lib/{queries,recipe-save,ingredient-input,price-impact,defaults,image}.ts`, `backend/shared/{pricing,pack,margin,menu-tags}.ts`, rotas `ingredients/**` (exceto `prices` e `use-price`, que são de `purchasing`), `recipes/**`, `categories/**`, `fixed-costs/**`, `settings`, `price-alerts/**`, `fotos/[id]`.

**Rotas:**

| Método | Caminho | Acesso |
|---|---|---|
| GET, POST | `/api/ingredients` | OWNER |
| PATCH, DELETE | `/api/ingredients/{id}` | OWNER |
| GET, POST | `/api/recipes` | OWNER |
| GET, PUT, DELETE | `/api/recipes/{id}` | OWNER |
| PUT, DELETE | `/api/recipes/{id}/photo` | OWNER |
| GET | `/api/recipes/cardapio` | OWNER |
| PATCH | `/api/recipes/{id}/cardapio` | OWNER |
| GET, POST | `/api/categories` | OWNER |
| PATCH, DELETE | `/api/categories/{id}` | OWNER |
| GET, POST | `/api/fixed-costs` | OWNER |
| PATCH, DELETE | `/api/fixed-costs/{id}` | OWNER |
| GET, PUT | `/api/settings` | OWNER |
| GET | `/api/price-alerts` | OWNER |
| POST | `/api/price-alerts/{id}` | OWNER |
| POST | `/api/price-alerts/{id}/apply` | OWNER |
| GET | `/api/fotos/{id}` | OWNER_OR_PUBLIC |

**Interfaces:**
- Produces: `catalog.api.CatalogApi` com `List<MenuRecipe> menuRecipes(OwnerId)` (nome, preço por unidade em `Money`, categoria, peso, tags, versão da foto, `showOnMenu`), `Optional<Money> unitPrice(OwnerId, long recipeId)`; `catalog.api.PricingEngine` (porte de `shared/pricing.ts`, `pack.ts`, `margin.ts` em `Money`); porta `PhotoStore` (`save`, `load`, `delete`) com adaptador Postgres (`recipe_photos`).
- Consumes: evento `OwnerRegistered` (cria `settings` e categorias padrão); evento `IngredientPriceChanged` (de `purchasing`) para recalcular alertas.

**Regras a preservar:** cálculo de receita (custo de ingrediente, mão de obra, rateio de custo fixo, margem, arredondamento para cima em R$ 0,50); foto: tipo verificado pelo conteúdo (JPEG/PNG/WebP), metadados EXIF/XMP removidos do JPEG, máximo 2 MB; cache `public, max-age=31536000, immutable` com `?v=`, `private, max-age=60` para a dona; foto pública só de receita da loja.

**Testes obrigatórios:** `PricingParityTest` com `rules-vectors/pricing.json` (gerado de `shared/pricing.ts`, 200 receitas aleatórias + os casos de `backend/tests/pricing.test.ts`) comparando **centavos exatos**; jqwik: preço sugerido nunca negativo e `rounded >= suggested`; receita vazia não gera erro; upload de 2 MB + 1 byte ⇒ 413 sem ler o corpo; PNG renomeado como JPEG é aceito como PNG; arquivo de texto ⇒ 400; JPEG com EXIF de GPS volta sem o segmento APP1 (comparar bytes); foto de receita de outro usuário ⇒ 404; ingrediente em uso ⇒ 409 `"Este item está em uso e não pode ser removido."`.

### Task 4.5: Módulo `purchasing` — fornecedores, cotações, busca de preço e nota fiscal

**Referência Node:** `backend/lib/{suppliers,nfce}.ts`, `backend/lib/scrapers/{tray,safe-url}.ts`, rotas `suppliers/**`, `prices/{link,quote,search}`, `ingredients/{id}/prices`, `ingredients/{id}/use-price`, `nota/{ler,importar}`.

**Rotas:** `GET, POST /api/suppliers`; `PATCH, DELETE /api/suppliers/{id}`; `POST /api/prices/link`, `POST /api/prices/quote`, `POST /api/prices/search`; `GET /api/ingredients/{id}/prices`; `POST /api/ingredients/{id}/use-price`; `POST /api/nota/ler`; `POST /api/nota/importar` — todas OWNER.

**Interfaces:**
- Produces: evento `IngredientPriceChanged(OwnerId, long ingredientId, Money before, Money after)`; job `purchasing.refresh-linked-prices` (diário).
- Porta `SupplierSiteClient` com adaptador HTTP que **só** aceita URLs aprovadas por `SafeUrl` (porte de `safe-url.ts`: https, sem IP privado/loopback/link-local após resolver o DNS, sem porta fora de 443) e tempo limite de 8 s.

**Regras a preservar:** a leitura de NFC-e recebe o HTML enviado pelo app (o servidor não busca portais externos); trava de nota repetida (`receipt_imports`); apelidos item→ingrediente (`receipt_aliases`); parser da loja Tray com o fixture `backend/tests/fixtures/tray-search.html` (copiar para `backend-java/modules/purchasing/src/test/resources/`).

**Testes obrigatórios:** os 19 casos de `backend/tests/nfce.test.ts` portados; `SafeUrl` recusa `http://`, `https://127.0.0.1`, `https://10.0.0.5`, `https://[::1]`, `https://metadata.google.internal`, e host cujo DNS resolve para IP privado (resolver de teste); importar a mesma nota duas vezes ⇒ 409; HTML de nota acima de 3 MB ⇒ 413.

### Task 4.6: Módulo `storefront` — cardápio público, story, documentos legais, retirada e configuração pública

**Referência Node:** `backend/lib/{shop-menu,pickup}.ts`, `backend/shared/{legal,shop,menu-tags}.ts`, rotas `menu`, `story/{cardapio,produto/[id]}`, `legal/[doc]`, `payment-config`, `cliente/config`, `pickup`, `pickup-addresses/**`.

**Rotas:** `GET /api/menu`, `GET /api/story/cardapio`, `GET /api/story/produto/{id}`, `GET /api/legal/{doc}`, `GET /api/payment-config`, `GET /api/cliente/config`, `GET /api/pickup` (PUBLIC); `GET, POST /api/pickup-addresses`, `PATCH, DELETE /api/pickup-addresses/{id}` (OWNER).

**Interfaces:**
- Produces: `storefront.api.StorefrontApi` com `Menu currentMenu()` (itens e grupos, preços em `Money`) e `Optional<PickupAddress> activePickupAddress(OwnerId)`.
- Consumes: `CatalogApi.menuRecipes(ShopOwner.id())`.

**Regras a preservar:** cardápio só com receitas `show_on_menu` e preço > 0; grupos ordenados com "Outros" por último (`localeCompare` pt-BR ⇒ `Collator` pt-BR); documentos legais com os textos de `shared/legal.ts` (copiar os textos para `src/main/resources/legal/*.md`, mesmo conteúdo, e o teste compara com o JSON gravado do Node).

**Testes obrigatórios:** `/api/menu` nunca contém `ingredients`, `cost`, `margin`, `custo`, `margem` (varredura do JSON); ordem dos grupos idêntica ao Node com nomes acentuados; `isLegalId("../etc/passwd")` ⇒ 404; contrato de cada rota pública com JSON Schema.

### Task 5.1: Módulo `orders` — pedidos manuais e da loja, numeração, eventos e cancelamento (G04, G09, G10, G11)

**Referência Node:** `backend/lib/{shop-queries,order-input,order-sync,order-events,order-code,cancel}.ts`, `backend/shared/shop.ts`, rotas `orders`, `orders/{id}`, `orders/shop/{id}/cancelar`, `cliente/pedidos` (GET, POST), `cliente/pedidos/{id}/cancelar`.

**Rotas:**

| Método | Caminho | Acesso |
|---|---|---|
| GET, POST | `/api/orders` | OWNER |
| PATCH, DELETE | `/api/orders/{id}` | OWNER |
| POST | `/api/orders/shop/{id}/cancelar` | OWNER |
| GET, POST | `/api/cliente/pedidos` | CUSTOMER |
| POST | `/api/cliente/pedidos/{id}/cancelar` | CUSTOMER |

**Interfaces:**
- Produces: `orders.api.OrdersApi` (`Optional<ShopOrderView> shopOrder(ShopOrderId)`, `Optional<ShopOrderView> shopOrderOf(ShopOrderId, CustomerId)`); `orders.api.ShopOrderLocking` (`Optional<ShopOrder> lockById(ShopOrderId)` com `SELECT … FOR UPDATE`, `void markPaid(ShopOrderId, String gatewayId, Money amount)`, `void markCancelled(...)`); portas que módulos de cima implementam: `orders.api.ports.ChargeStarter` (payments: gera o Pix ou cobra o cartão de um pedido recém-criado), `orders.api.ports.PendingChargeCanceller` (payments: cancela Pix pendente e consulta se pagou), `orders.api.ports.PaidOrderCanceller` (refunds: cancela pedido pago devolvendo o dinheiro); eventos `ShopOrderCreated`, `ShopOrderCancelled`, `OrderStatusChanged`.
- `OrderNumberAllocator.next(OwnerId)` — `pg_advisory_xact_lock(owner)` + `MAX(number)+1`, usado por pedido manual **e** pedido da loja (G10); `PublicCodeAllocator` — porte de `order-code.ts` (4–5 dígitos, 30 tentativas).

**Regras a preservar:** todas as de `backend/app/api/orders/[id]/route.ts` (linha de pedido do app não é excluída, não muda item/valor, não reabre se encerrada, cancelar só pelo botão próprio), `parseShopOrder` de `shared/shop.ts` (validações, prazo mínimo, janela de horário, CPF no cartão, preços lidos do cardápio no servidor), endereço gravado no pedido, retirada com endereço da loja congelado.

**Mudanças:** G11 — criar pedido aplica `RateLimits.ORDER_CREATE` e recusa com 429 `"Você fez muitos pedidos em pouco tempo. Tente de novo mais tarde."` se o cliente tiver 3 pedidos sem pagamento em aberto; G09 — cancelamento que descobre Pix pago chama o `ConfirmPaymentUseCase` (via porta `PendingChargeCanceller`) antes de decidir.

**Testes obrigatórios:**
- `OrderNumberConcurrencyIT` (T06): 20 threads criando pedido manual e 20 criando pedido da loja para a mesma dona ao mesmo tempo ⇒ 40 pedidos, números 1..40 sem repetição e sem erro.
- `CancelVsWebhookIT` (T06): cancelamento do cliente e confirmação de pagamento do mesmo pedido ao mesmo tempo ⇒ estado final coerente: ou (cancelado + devolução registrada) ou (pago + não cancelado), nunca (cancelado + pago sem devolução).
- Contrato: os 4 casos de `backend/tests/orders-route.integration.test.ts` **com sessão real** (sem simular `getUser`); outra dona/outro cliente ⇒ 404; preço manipulado no corpo do pedido é ignorado (vale o do cardápio).
- `AbuseIT` (T07): 11º pedido na mesma hora ⇒ 429; 4º pedido sem pagar ⇒ 429.

### Task 5.2: Módulo `payments` — gateway Mercado Pago, Pix, cartão, link de pagamento e webhook (G02, G03, G04, G09, G18)

**Referência Node:** `backend/lib/{mercadopago,pix,card-pay,pay-link}.ts`, rotas `cliente/pedidos/{id}/pix`, `cliente/pedidos/{id}/cartao`, `orders/{id}/pagamento`, `pagar/{token}`, `pagar/{token}/pix`, `pagar/{token}/cartao`, `pagar/{token}/cancelar`, `webhooks/mercadopago`.

**Rotas:**

| Método | Caminho | Acesso |
|---|---|---|
| POST | `/api/cliente/pedidos/{id}/pix` | CUSTOMER |
| POST | `/api/cliente/pedidos/{id}/cartao` | CUSTOMER |
| POST | `/api/orders/{id}/pagamento` | OWNER |
| GET | `/api/pagar/{token}` | PAY_LINK_TOKEN |
| POST | `/api/pagar/{token}/pix` | PAY_LINK_TOKEN |
| POST | `/api/pagar/{token}/cartao` | PAY_LINK_TOKEN |
| POST | `/api/pagar/{token}/cancelar` | PAY_LINK_TOKEN |
| POST | `/api/webhooks/mercadopago` | SIGNED_WEBHOOK |

**Interfaces:**
- Produces: `payments.api.PaymentsApi` (`void refundPayment(String gatewayId, Money amountOrNull, String idempotencyKey)` usado por `refunds`); implementações das portas `ChargeStarter` e `PendingChargeCanceller` de `orders`; `ConfirmPaymentUseCase` (modelo na seção "Padrão de código") — **único** caminho que marca pago; `CardChargeUseCase`; `PayLinkUseCases`; `WebhookUseCase`; jobs `payments.reconcile-links`, `payments.expire-stale-attempts`.
- Porta `PaymentGateway` (`createPix`, `createCard`, `getPayment`, `cancel`, `refund`, `listRefunds`) com adaptador `MercadoPagoClient` (`RestClient`, base `MP_API_BASE`, tempo limite de conexão 3 s e de leitura 10 s, cabeçalho `X-Idempotency-Key`).
- Porta `RefundRequester` (implementada por `refunds`, usada no passo 3/6 do `ConfirmPaymentUseCase`).

**Regras a preservar:** chave de idempotência do Pix `rayka-<order-N|link-N>-<tentativa>`; validade do Pix entre 31 min e 30 dias (padrão 60); `mpDate` sempre `-03:00`; mensagens de recusa de cartão (tabela `CARD_FAIL_REASONS`); `external_reference` = id do pedido ou `link:<id>`; webhook sempre reconsulta o pagamento no gateway; reembolso/chargeback recebido ⇒ conciliação de devoluções; assinatura `id:<data.id>;request-id:<x-request-id>;ts:<ts>;` com HMAC-SHA256 (id em minúsculas se alfanumérico).

**Mudanças:**
- G03: `CardChargeUseCase` grava `payment_attempts(state='in_flight')` **antes** de chamar o gateway (o índice único parcial faz a 2ª tentativa simultânea falhar com 409 `"Já existe um pagamento em andamento para este pedido. Aguarde alguns segundos."`); a chave de idempotência do cartão passa a ser `rayka-card-<uuid da tentativa>`; o resultado aprovado vai para `ConfirmPaymentUseCase`.
- G02/G09: cartão aprovado, Pix aprovado no cancelamento e conciliação usam `ConfirmPaymentUseCase` (pedido cancelado ⇒ devolução; pagamento duplicado ⇒ devolução + `OpsAlert.paymentProblem`).
- G04: antes de cobrar o cartão, `RateLimits.CARD_FAIL_CUSTOMER` e `CARD_FAIL_IP` (só recusas contam); estourado ⇒ 429 `"Muitas tentativas de cartão. Tente pagar com Pix ou fale com a gente."`.
- G18: webhook recusa `ts` fora de ±10 min (401 `"Assinatura inválida"`) e ignora `x-request-id` já processado (`webhook_deliveries`, responde 200 `{"ok":true,"duplicate":true}`).
- Cartão completo conforme a documentação (Tarefa 0.3): o corpo de criação do pagamento envia `issuer_id` (recebido do site/app junto com o token, vindo de `installments` → `issuer.id`; o DTO aceita o campo novo `card_issuer_id` como opcional para não quebrar APKs antigos, e, quando vier ausente, o servidor o busca por `GET /v1/payment_methods/installments?bin=<6 primeiros>`. De onde vem o bin nesse caso é decidido na Tarefa 0.3: se a consulta do token pelo servidor (`GET /v1/card_tokens/{token}`) for documentada e funcionar no sandbox, usa ela; se não, o site/app passam a enviar `card_bin` junto com o token — o que exige mudança no `frontend/` e nos apps, registrada como pendência do corte), `payer.identification`, `payer.first_name`, `X-Idempotency-Key` = UUID da tentativa. Se o diagnóstico da 0.3 apontar outros campos obrigatórios, eles entram aqui com teste.

**Parte B — suíte ao vivo contra o sandbox (`-Psandbox`, T13):**
- [ ] `MercadoPagoSandboxIT` (só roda com `MP_SANDBOX_*` definidos; **sem** eles, o perfil `sandbox` falha em vez de pular): verificação prévia de credencial de teste (mesma regra da 0.3, nenhuma exceção vira sucesso); para cada nome mágico, tokeniza de verdade por `POST /v1/card_tokens?public_key=…` e chama o **adaptador Java real** (`MercadoPagoClient.createCard`) — confere `status`/`status_detail` da tabela; APRO repetido com a mesma chave de idempotência ⇒ mesmo `id` de pagamento; devolução total e parcial do APRO; Pix criado traz `qr_code`; webhook: assinatura de uma notificação real do sandbox (quando houver, Tarefa 7.2) valida com o `WebhookSignatureVerifier`.
- [ ] `RecordingsDriftIT`: compara a resposta ao vivo de cada chamada com a gravada (só status, campos e tipos, não valores) e falha listando as diferenças — sinal para regravar (Tarefa 3.1).
- [ ] CI: job noturno `mercadopago-sandbox` com os segredos `MP_SANDBOX_PUBLIC_KEY`, `MP_SANDBOX_ACCESS_TOKEN`, `MP_SANDBOX_BUYER_EMAIL`; falha abre alerta (não bloqueia PR, porque depende de serviço externo).

**Testes obrigatórios (todos com Postgres real e o fake do Mercado Pago montado com as gravações reais da Tarefa 3.1 — nunca repositório simulado, T04):**
- `CardMagicNamesIT`: um pedido de cartão por nome mágico, de ponta a ponta pela rota HTTP `POST /api/cliente/pedidos`: APRO ⇒ pedido pago + push para a dona e para o cliente; CONT ⇒ pendente, sem "pago"; cada recusa ⇒ pedido criado sem pagamento e a mensagem em português da tabela `CARD_FAIL_REASONS` (nenhuma recusa documentada cai na mensagem genérica).
- `CardRequestBodyIT`: o corpo que chega ao fake em `POST /v1/payments` contém `token`, `transaction_amount` (em reais com 2 casas, igual ao total do pedido), `installments`, `payment_method_id`, `issuer_id`, `payer.email`, `payer.first_name`, `payer.identification` com CPF só dígitos, `external_reference` = id do pedido, `notification_url` = `SITE_URL + /api/webhooks/mercadopago`, e cabeçalho `X-Idempotency-Key` em formato UUID.
- `MercadoPagoSignatureTest`: secret `segredo-webhook`, dataId `123456`, request-id `req-1`, ts `1700000000` ⇒ `v1=5d8a4111a1d2d2e831b3436b3448176d291c922a704d059220b048e0b21b9231` aceito (vetor do Node, com relógio fixo em `1700000000`); mesmo `v1` com relógio 11 min depois ⇒ recusado.
- `ConfirmPaymentConcurrencyIT` (T05, T06): duas aprovações diferentes do mesmo pedido em paralelo (2 threads + latch) ⇒ exatamente uma vira `paid_mp_id`, a outra gera **uma** devolução integral no fake (`POST /v1/payments/{id}/refunds` chamado 1 vez com a chave de idempotência certa) e um alerta.
- `CardOnCancelledOrderIT` (T05): pedido cancelado, cartão aprovado ⇒ pedido fica pago **e** devolução integral pedida; resposta ao cliente `{"approved":true}` + evento de devolução.
- `ConcurrentCardAttemptsIT` (T06): duas chamadas `POST /api/cliente/pedidos/{id}/cartao` simultâneas ⇒ uma processa, a outra 409; o fake recebe uma só cobrança.
- `WebhookIT`: valor menor que o total ⇒ não paga; id de pagamento que não é do pedido ⇒ não paga; reenvio ⇒ idempotente (um só evento "pago", um só push); pagamento de pedido cancelado ⇒ devolução; `refunded`/`charged_back` ⇒ conciliação.
- `MercadoPagoResilienceIT` (T09): fake com atraso de 15 s ⇒ cliente recebe 502 `"Não conseguimos falar com o Mercado Pago agora. Tente de novo ou pague com Pix."` em ≤ 11 s, tentativa marcada `error`, job `expire-stale-attempts` fecha depois consultando o gateway.
- Os 63 casos de `backend/tests/pay-link.integration.test.ts` portados (já usam banco real; manter as corridas).
- `AbuseIT` (T07): 6 recusas de cartão do mesmo cliente em pedidos diferentes ⇒ a 6ª tentativa é 429 sem chamar o gateway.
- Pitest ≥ 80 % em `payments.domain` (T11).

### Task 5.3: Módulo `refunds` — devoluções, fotos, novas tentativas e conciliação (G08, G12)

**Referência Node:** `backend/lib/{refunds,refund-form,jobs}.ts`, `backend/shared/refund.ts`, rotas `devolucoes/**`, `cliente/pedidos/{id}/devolucao`, `cliente/devolucoes/{rid}/fotos/{n}`, `orders/shop/{id}/devolver`, `pagar/{token}/devolucao`.

**Rotas:**

| Método | Caminho | Acesso |
|---|---|---|
| GET | `/api/devolucoes` | OWNER |
| GET | `/api/devolucoes/{id}` | OWNER |
| POST | `/api/devolucoes/{id}/decisao` | OWNER |
| POST | `/api/devolucoes/{id}/tentar` | OWNER |
| GET | `/api/devolucoes/{id}/fotos/{n}` | OWNER |
| POST | `/api/orders/shop/{id}/devolver` | OWNER |
| POST, DELETE | `/api/cliente/pedidos/{id}/devolucao` | CUSTOMER |
| GET | `/api/cliente/devolucoes/{rid}/fotos/{n}` | CUSTOMER |
| POST, DELETE | `/api/pagar/{token}/devolucao` | PAY_LINK_TOKEN |

**Interfaces:**
- Produces: implementação de `RefundRequester` (payments) e de `PaidOrderCanceller` (orders); jobs `refunds.retry-due`, `refunds.reconcile-pending`, `refunds.cleanup-old-photos`; eventos `RefundRequested`, `RefundCompleted`, `RefundFailed`.
- Consumes: `PaymentsApi.refundPayment`, `ShopOrderLocking`, `PhotoStore` (fotos de devolução).

**Regras a preservar:** janela de devolução (`REFUND_WINDOW_HOURS`), motivos e rótulos de `shared/refund.ts`, devolução parcial/total, classificação de falha (`saldo` / `prazo` / `erro`) e novas tentativas, fotos até 2 MB cada (até 5 por pedido), limpeza das fotos 180 dias após a decisão.

**Testes obrigatórios:** `RefundRulesParityTest` com `rules-vectors/refund-rules.json` (gerado de `shared/refund.ts`); soma das devoluções parciais nunca passa do valor pago (jqwik, centavos exatos — T08); os 18 casos de `backend/tests/refunds.integration.test.ts` portados; devolução que falha por saldo ⇒ estado `saldo`, nova tentativa agendada, `OpsAlert.refundFailed`; mesma devolução pedida duas vezes em paralelo ⇒ um só `POST /refunds` no fake; foto de 2 MB + 1 byte ⇒ 413; foto de devolução de outro cliente ⇒ 404.

### Task 6.1: Módulo `feedback`

**Referência Node:** `backend/lib/{feedback,feedback-form}.ts`, `backend/shared/feedback.ts`, rotas `cliente/feedback`, `cliente/feedback/{id}/fotos/{n}`, `feedback`, `feedback/{id}/fotos/{n}`.

**Rotas:** `POST /api/cliente/feedback`, `GET /api/cliente/feedback/{id}/fotos/{n}` (CUSTOMER); `GET /api/feedback`, `GET /api/feedback/{id}/fotos/{n}` (OWNER).

**Testes obrigatórios:** os 5 casos de `backend/tests/feedback.test.ts`; feedback com 6 fotos ⇒ 400; foto de outro cliente ⇒ 404; corpo de 13 MB ⇒ 413 sem leitura.

### Task 6.2: Módulo `insights` — dashboard e metas

**Referência Node:** `backend/lib/dashboard.ts`, rotas `dashboard`, `goals`.

**Rotas:** `GET /api/dashboard`; `GET, PUT /api/goals` (OWNER).

**Testes obrigatórios:** dashboard com pedidos de dois meses separa pelo mês no fuso de São Paulo (pedido às 23h30 de 31/10 no horário local é de outubro, mesmo sendo novembro em UTC); totais em centavos exatos; meta inválida (negativa) ⇒ 400.

### Task 6.3: Módulo `releases` — APKs, manifesto e links assinados (G01, G14)

**Referência Node:** `backend/lib/downloads.ts`, rotas `downloads`, `downloads/{app}`, `downloads/{app}/link`.

**Rotas:** `GET /api/downloads` (OWNER_OR_PUBLIC), `GET /api/downloads/{app}` (OWNER_OR_PUBLIC; `server` exige dona ou link assinado), `POST /api/downloads/{app}/link` (OWNER).

**Regras a preservar:** manifesto `downloads/manifest.json`; app listado só se o arquivo existir e o nome casar `^[\w.+-]+\.apk$`; `Range` (206/416), `etag` = sha256, `x-app-version`, `cache-control: no-store`; link assinado `exp.HMAC_SHA256(secret, "<app>.<exp>")` em base64url sem padding, 5 min.

**Mudanças:** G01 — app `server` aparece em `/api/downloads` e baixa só para a dona (`ShopOwner`); G14 — segredo vem só de `DOWNLOAD_SECRET`; G30 — download por `StreamingResponseBody` com aborto do cliente tratado.

**Testes obrigatórios:** `DownloadLinkSignerTest`: secret `segredo-download`, app `server`, exp `1900000000` ⇒ assinatura `rDAAQzxT_Hy8VmtTbE94nYAl_OYFlEDWd_tt9z6sVkk` (vetor do Node); link vencido ⇒ 401; `Range: bytes=-10` ⇒ últimos 10 bytes com 206; `Range: bytes=999999999-` ⇒ 416; manifesto com `file: "../../etc/passwd"` ⇒ app não listado; conta que não é a dona não vê nem baixa o `server` (T07); `DownloadAbortIT` (G30): com um APK de 50 MB, 20 clientes abrem o download e fecham a conexão depois de 1 MB ⇒ nenhum erro `ERROR` no log, `/api/health` segue 200 e um download completo seguinte tem o sha256 certo.

### Task 6.4: Saúde, `/api/health` e o fim do `/api/jobs/tick` (G13)

**Rotas:** `GET /api/health` (PUBLIC) — 200 `{"ok":true}` com banco no ar; 503 `{"ok":false}` se o `SELECT 1` falhar ou demorar mais de 2 s. `POST /api/jobs/tick` passa a responder 410 `{"error":"Tarefas agendadas rodam dentro do servidor."}` (marcado `@ChangedFromNode("G13")`), para que nenhum agendador externo antigo dependa dela.

**Testes obrigatórios:** banco pausado ⇒ `/api/health` 503 em ≤ 3 s (T09); `AccessInventoryTest` completo com as 111 linhas do Apêndice A.

---

## Fase 7 — Verificação final e corte

### Task 7.1: Paridade total, abuso, resiliência e cobertura

- [ ] Rodar a suíte de contrato inteira com `CONTRACT_TARGET=node` (passa) e com `CONTRACT_TARGET=java` (passa, incluindo os `@ChangedFromNode`).
- [ ] `AccessMatrixIT` (T03): para **cada** linha do Apêndice A, chamar sem sessão, com sessão de cliente, com sessão de dona e com sessão de usuário que não é a dona, e conferir o status esperado pela coluna Acesso.
- [ ] `AbuseIT` completo (T07) e `ResilienceIT` completo (T09).
- [ ] `./mvnw -f backend-java/pom.xml verify -Pmutation` ⇒ JaCoCo e Pitest dentro dos mínimos (T11).
- [ ] Verificar os vetores de paridade (G25): job de CI `rules-parity` regenera os JSON a partir do TypeScript (`frontend/` + `shared/`) e compara com `backend-java/rules-vectors/`; diferença ⇒ falha com instrução para atualizar os dois lados.
- [ ] Commit `test: paridade, abuso, resiliência e cobertura do backend Java`.

### Task 7.2: Ensaio com cópia do banco de produção

- [ ] `pg_dump` do banco de produção para um arquivo (somente leitura na produção); restaurar num Postgres **separado** `rayka_staging`.
- [ ] Subir o Java apontando para `rayka_staging` (porta 10007, `REGISTRATION_ENABLED=false`, `MP_ACCESS_TOKEN` de **teste**, `JOBS_ENABLED=false`). Flyway aplica só V22+.
- [ ] Rodar a suíte de contrato de leitura (rotas GET) e os testes de `NodeSessionCompatibilityIT` usando uma sessão real copiada.
- [ ] Subir o Node (imagem atual) contra o mesmo `rayka_staging` **depois** das V22+ e rodar a suíte de contrato com `CONTRACT_TARGET=node`: prova que a volta (rollback) continua possível.
- [ ] Comparar respostas Node × Java para todas as rotas GET da dona e do cliente (diferença só onde há `@ChangedFromNode`).
- [ ] **Mercado Pago de ponta a ponta no ensaio**, com as credenciais de **teste** e o Java exposto num endereço público temporário (túnel aprovado pelo dono) como `notification_url`: (1) pedido com Pix de teste pago pelo comprador de teste ⇒ webhook real chega, assinatura valida, pedido vira pago; (2) pedido com cartão APRO pelo **site de verdade** (frontend apontando para o ensaio) e pelo **APK** ⇒ tokenização real no navegador/app, pagamento aprovado; (3) "Simular notificação" no painel do Mercado Pago ⇒ salvar o corpo e os cabeçalhos recebidos como fixture `contract-tests/src/test/resources/mp-recordings/webhook-real.json` (usado no `WebhookIT`).
- [ ] **Google de ponta a ponta no ensaio:** login com uma conta Google de teste pelo site (fluxo OAuth com `rk_oauth`) e pelo APK (`id_token`), com o `GOOGLE_CLIENT_ID` real e o redirecionamento do ensaio cadastrado no console do Google.
- [ ] **Push de ponta a ponta no ensaio:** novo pedido ⇒ notificação chega no aparelho de teste com o app de gestão.

### Task 7.3: Corte em produção com volta garantida (G29)

- [ ] Script `backend-java/deploy/deploy.sh`: 1) `pg_dump` de backup com data no nome; 2) `docker build -t raykookie-api-java:<commit> backend-java`; 3) `docker stop raykookie-api` (mantém o container Node parado, **sem** remover); 4) `docker run -d --name raykookie-api-java --network host --restart unless-stopped --env-file backend/.env.docker -v ~/.raykookie:/home/g_snts/.raykookie:ro -v "$(pwd)/downloads:/app/downloads" raykookie-api-java:<commit>`; 5) espera `/api/health` 200 por até 60 s; 6) roda a suíte de contrato de leitura contra `https://raykookie-api.kntap.com.br`; falha em 5 ou 6 ⇒ executa a volta automaticamente.
- [ ] Script `backend-java/deploy/rollback.sh`: `docker stop raykookie-api-java && docker start raykookie-api`.
- [ ] Variáveis novas no `.env` de produção: `DOWNLOAD_SECRET` (gerar com `openssl rand -base64 48`; hoje **não existe** em produção), `REGISTRATION_ENABLED=false`, `TRUSTED_PROXIES=127.0.0.1/32,::1/128`, `MP_PUBLIC_KEY` de produção (hoje **não existe** — G31), `PAYMENTS_CARD_ENABLED=true` só depois do pagamento real de valor baixo feito pelo dono e devolvido, `EDGE_SHARED_SECRET` se a medição da Tarefa 2.4 exigir (junto com o middleware no `frontend/`); manter `PORT=20010`; `COOKIE_SECURE` sem comentário na mesma linha.
- [ ] Acompanhar 14 dias (alertas, métricas, logs). Depois: remover o container Node, arquivar `backend/` em `legacy/backend-node/`, atualizar README, `docs/ARQUITETURA.md`, `docs/API.md` (passa a apontar para `/api/openapi.json`) e o CI.

### Task 8.1 (opcional, depois do corte): Fotos fora do banco (G27)

- [ ] Adaptador `FilesystemPhotoStore` (`PHOTOS_DIR`, nomes por sha256 do conteúdo), migração em segundo plano que copia `recipe_photos`/`refund_photos`/`feedback_photos` para o disco, leitura com fallback para o banco, e só depois uma migration "contrair" (apagar os bytes) — que **encerra a possibilidade de voltar ao Node** e por isso exige aprovação explícita do dono.

---

## Apêndice A — Inventário de rotas a manter (111 combinações método+caminho)

Fonte: varredura de `backend/app/api/**/route.ts` em 2026-10-07. Vira `expected-routes.txt` na Tarefa 2.5. Coluna Acesso já aplica G01/G24 (dona = `SHOP_OWNER`).

```
POST   /api/auth/login                          PUBLIC
POST   /api/auth/logout                         PUBLIC
POST   /api/auth/register                       PUBLIC
GET    /api/categories                          OWNER
POST   /api/categories                          OWNER
PATCH  /api/categories/{id}                     OWNER
DELETE /api/categories/{id}                     OWNER
POST   /api/cliente/cadastro                    PUBLIC
GET    /api/cliente/config                      PUBLIC
POST   /api/cliente/devices                     CUSTOMER
DELETE /api/cliente/devices                     CUSTOMER
GET    /api/cliente/devolucoes/{rid}/fotos/{n}  CUSTOMER
POST   /api/cliente/feedback                    CUSTOMER
GET    /api/cliente/feedback/{id}/fotos/{n}     CUSTOMER
GET    /api/cliente/google                      PUBLIC
GET    /api/cliente/google/callback             PUBLIC
POST   /api/cliente/google/token                PUBLIC
POST   /api/cliente/login                       PUBLIC
POST   /api/cliente/logout                      PUBLIC
GET    /api/cliente/me                          CUSTOMER
PATCH  /api/cliente/me                          CUSTOMER
DELETE /api/cliente/me                          CUSTOMER
GET    /api/cliente/me/dados                    CUSTOMER
GET    /api/cliente/me/exportar                 CUSTOMER
DELETE /api/cliente/me/google                   CUSTOMER
POST   /api/cliente/me/privacidade              CUSTOMER
GET    /api/cliente/pedidos                     CUSTOMER
POST   /api/cliente/pedidos                     CUSTOMER
POST   /api/cliente/pedidos/{id}/cancelar       CUSTOMER
POST   /api/cliente/pedidos/{id}/cartao         CUSTOMER
POST   /api/cliente/pedidos/{id}/devolucao      CUSTOMER
DELETE /api/cliente/pedidos/{id}/devolucao      CUSTOMER
POST   /api/cliente/pedidos/{id}/pix            CUSTOMER
GET    /api/dashboard                           OWNER
POST   /api/devices                             OWNER
DELETE /api/devices                             OWNER
GET    /api/devolucoes                          OWNER
GET    /api/devolucoes/{id}                     OWNER
POST   /api/devolucoes/{id}/decisao             OWNER
GET    /api/devolucoes/{id}/fotos/{n}           OWNER
POST   /api/devolucoes/{id}/tentar              OWNER
GET    /api/downloads                           OWNER_OR_PUBLIC
GET    /api/downloads/{app}                     OWNER_OR_PUBLIC
POST   /api/downloads/{app}/link                OWNER
GET    /api/feedback                            OWNER
GET    /api/feedback/{id}/fotos/{n}             OWNER
GET    /api/fixed-costs                         OWNER
POST   /api/fixed-costs                         OWNER
PATCH  /api/fixed-costs/{id}                    OWNER
DELETE /api/fixed-costs/{id}                    OWNER
GET    /api/fotos/{id}                          OWNER_OR_PUBLIC
GET    /api/goals                               OWNER
PUT    /api/goals                               OWNER
GET    /api/health                              PUBLIC
GET    /api/ingredients                         OWNER
POST   /api/ingredients                         OWNER
PATCH  /api/ingredients/{id}                    OWNER
DELETE /api/ingredients/{id}                    OWNER
GET    /api/ingredients/{id}/prices             OWNER
POST   /api/ingredients/{id}/use-price          OWNER
POST   /api/jobs/tick                           PUBLIC
GET    /api/legal/{doc}                         PUBLIC
GET    /api/menu                                PUBLIC
GET    /api/me                                  OWNER
POST   /api/nota/importar                       OWNER
POST   /api/nota/ler                            OWNER
GET    /api/orders                              OWNER
POST   /api/orders                              OWNER
PATCH  /api/orders/{id}                         OWNER
DELETE /api/orders/{id}                         OWNER
POST   /api/orders/{id}/pagamento               OWNER
POST   /api/orders/shop/{id}/cancelar           OWNER
POST   /api/orders/shop/{id}/devolver           OWNER
GET    /api/pagar/{token}                       PAY_LINK_TOKEN
POST   /api/pagar/{token}/cancelar              PAY_LINK_TOKEN
POST   /api/pagar/{token}/cartao                PAY_LINK_TOKEN
POST   /api/pagar/{token}/devolucao             PAY_LINK_TOKEN
DELETE /api/pagar/{token}/devolucao             PAY_LINK_TOKEN
POST   /api/pagar/{token}/pix                   PAY_LINK_TOKEN
GET    /api/payment-config                      PUBLIC
GET    /api/pickup                              PUBLIC
GET    /api/pickup-addresses                    OWNER
POST   /api/pickup-addresses                    OWNER
PATCH  /api/pickup-addresses/{id}               OWNER
DELETE /api/pickup-addresses/{id}               OWNER
GET    /api/price-alerts                        OWNER
POST   /api/price-alerts/{id}                   OWNER
POST   /api/price-alerts/{id}/apply             OWNER
POST   /api/prices/link                         OWNER
POST   /api/prices/quote                        OWNER
POST   /api/prices/search                       OWNER
GET    /api/privacy-requests                    OWNER
POST   /api/privacy-requests/{id}               OWNER
GET    /api/recipes                             OWNER
POST   /api/recipes                             OWNER
GET    /api/recipes/{id}                        OWNER
PUT    /api/recipes/{id}                        OWNER
DELETE /api/recipes/{id}                        OWNER
PUT    /api/recipes/{id}/photo                  OWNER
DELETE /api/recipes/{id}/photo                  OWNER
GET    /api/recipes/cardapio                    OWNER
PATCH  /api/recipes/{id}/cardapio               OWNER
GET    /api/settings                            OWNER
PUT    /api/settings                            OWNER
GET    /api/story/cardapio                      PUBLIC
GET    /api/story/produto/{id}                  PUBLIC
GET    /api/suppliers                           OWNER
POST   /api/suppliers                           OWNER
PATCH  /api/suppliers/{id}                      OWNER
DELETE /api/suppliers/{id}                      OWNER
POST   /api/webhooks/mercadopago                SIGNED_WEBHOOK
```

(São 111 linhas, contadas da varredura. `OPTIONS`/`HEAD` gerados pelo Spring não entram na lista e o `AccessInventoryTest` os ignora. Se uma rota nova surgir no Node antes do corte, atualizar esta lista e o `expected-routes.txt` juntos.)

## Apêndice B — Vetores de ouro gerados no Node (2026-10-07)

| Uso | Entrada | Saída esperada |
|---|---|---|
| Senha (scrypt N=16384, r=8, p=1, 64 bytes) | senha `senha-de-teste-123`, sal hex `00112233445566778899aabbccddeeff` | `scrypt$00112233445566778899aabbccddeeff$216992f0b9009b3af7a1ae842f0b167c45e4c876e89b9ebea0aae61d62c690a42dea16df88ba4d801cf641c80e33edeacb7d65df1e739162cb307c6536e905ff` |
| Hash de sessão (sha256 hex) | `token-abc` | `096157b339cf419dbc0c8def49fc2be924dc130fbacdfa7b879bee0a3078cec4` |
| Assinatura do webhook MP (HMAC-SHA256 hex) | secret `segredo-webhook`, manifesto `id:123456;request-id:req-1;ts:1700000000;` | `5d8a4111a1d2d2e831b3436b3448176d291c922a704d059220b048e0b21b9231` |
| Link de download (HMAC-SHA256 base64url) | secret `segredo-download`, mensagem `server.1900000000` | `rDAAQzxT_Hy8VmtTbE94nYAl_OYFlEDWd_tt9z6sVkk` |

## Apêndice C — Matriz de rastreabilidade

| Achado | Tarefa(s) |
|---|---|
| G01 | 0.2, 2.5, 4.1, 6.3 |
| G02 | 0.2, 5.2 |
| G03 | 0.2, 2.2 (tabela), 5.2 |
| G04 | 2.4, 5.2 |
| G05 | 0.2, 2.4, 4.1, 4.2 |
| G06 | 2.2 |
| G07 | 2.2 |
| G08 | 2.3, 4.4, 5.3, 6.1 |
| G09 | 5.1, 5.2 |
| G10 | 5.1 |
| G11 | 2.4, 5.1 |
| G12 | 2.1, 4.4, 5.3, 6.2 |
| G13 | 2.7, 6.4 |
| G14 | 2.6, 6.3 |
| G15 | 2.6 |
| G16 | 2.7, 4.1, 4.2 |
| G17 | 4.3 |
| G18 | 2.2 (tabela), 5.2 |
| G19 | 4.2 |
| G20 | 2.3 |
| G21 | 1.1, 2.5 |
| G22 | 1.2 (ArchUnit), todas as tarefas de módulo |
| G23 | 2.4, 2.7 |
| G24 | 2.5, 4.1 |
| G25 | 2.1, 4.4, 5.3, 7.1 |
| G26 | 3.0, 7.1, 7.3 |
| G27 | 4.4 (porta), 8.1 |
| G28 | 2.8 |
| G29 | 2.2, 7.3 |
| G30 | 6.3 |
| G31 | 0.3, 2.6, 5.2, 7.3 |
| T01 | 0.1, 1.2 (IsolationTest) |
| T02 | 3.0, todas as tarefas de módulo |
| T03 | 2.5, 7.1 |
| T04 | 1.2 (regra ArchUnit 7), 5.1, 5.2, 5.3 |
| T05 | 0.2, 5.2 |
| T06 | 2.4, 2.7, 5.1, 5.2, 5.3 |
| T07 | 4.1, 4.2, 5.1, 5.2, 6.3, 7.1 |
| T08 | 2.1, 4.4, 5.3 |
| T09 | 2.2, 5.2, 6.4, 7.1 |
| T10 | 2.2 |
| T11 | 1.2, 2.1, 5.2, 7.1 |
| T12 | 0.1, 2.2 (Testcontainers) |
| T13 | 0.3, 3.1, 5.2 (parte B) |
| Banco de teste igual ao de produção | 2.2 (21 migrations reais, `postgres:17.11`), 7.2 (cópia do banco) |
| Mercado Pago fiel à realidade | 0.3, 3.1, 5.2 (+ parte B), 7.2 |
