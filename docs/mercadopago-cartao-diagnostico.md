# Diagnóstico do pagamento com cartão (Mercado Pago)

Tarefa 0.3 do plano de reconstrução do backend em Java. Execução: 2026-10-08, contra `api.mercadopago.com` real.
Suíte ao vivo: `backend/tests/card-pay.sandbox.test.ts`.

Nenhuma credencial aparece neste documento: as chaves são citadas só pelo nome da variável em
`~/.raykookie/mercadopago.env`. Ids e e-mails estão mascarados.

## Resumo

| # | Hipótese | Veredito |
|---|---|---|
| H1 | `MP_PUBLIC_KEY` ausente em produção ⇒ cartão desligado | **confirmada**. Basta ela para o cartão não funcionar em produção. |
| H2 | Testes rodaram com credenciais "de produção" e cartões de teste ⇒ `Unauthorized use of live credentials` | **confirmada, com a causa reformulada**: o erro se repete com a conta de teste do dono. A causa é o tipo de credencial (`APP_USR-` de um aplicativo em `sandbox_mode`), não a conta. |
| H3 | `issuer_id` não é enviado | **não testável** com as credenciais atuais (todo `POST /v1/payments` volta 401). Confirmada só no código. |
| H4 | E-mail do pagador inválido no ambiente | **não testável** com as credenciais atuais (o 401 vem antes de qualquer validação do pagador) |
| H5 | `payment_method_id` errado | **descartada** pela parte que dá para verificar: site e app mandam o `payment_method_id` certo para os 4 cartões. O efeito de um id errado no pagamento não foi testável. |
| H6 | Chave pública e access token de ambientes diferentes | **descartada no código**. Na API, a combinação errada não foi testável porque não temos um segundo par de teste e o uso de produção é proibido. |

**Por que o cartão não funciona em produção:** H1. O container `raykookie-api` não tem `MP_PUBLIC_KEY`,
então `GET /api/payment-config` devolve `{"cardEnabled":false,"publicKey":null}`. O site e o app escondem a opção
"cartão", e `payWithCard` responde 503. Por isso ninguém chegou a criar um pagamento com cartão em produção.
O Pix funciona porque só depende de `MP_ACCESS_TOKEN`.

**Por que o cartão não funciona nos testes:** o par `MP_SANDBOX_*` atual é a credencial `APP_USR-` de um
aplicativo criado dentro da conta do **vendedor de teste**. Esse aplicativo tem `sandbox_mode: true` e
`certification_status: "not_certified"`. Com esse par, o Mercado Pago aceita tokenizar cartões, consultar bandeiras,
fazer buscas e criar preferências. Mas **todo** `POST /v1/payments` volta
`401 {"message":"Unauthorized use of live credentials","cause":[{"code":7}]}`, **inclusive Pix**. Ainda falta
confirmar com um par que funcione que a chave de teste (`TEST-`) resolve. Até lá, nenhum nome mágico, nem H3
ou H4, pode ser medido.

**Ação do dono (bloqueia o resto da 0.3 e a gravação da 5.2).** Escolher uma das opções e regravar
`MP_SANDBOX_PUBLIC_KEY` / `MP_SANDBOX_ACCESS_TOKEN` em `~/.raykookie/mercadopago.env`:

1. **(recomendado)** Entrar no painel com o usuário **vendedor de teste**, abrir o aplicativo dele e copiar as
   **Credenciais de teste**, que começam com `TEST-`. A API confirma que esse aplicativo tem um `test_access_token`
   com prefixo `TEST`, diferente do token que está no arquivo. Esse token não foi usado, porque não veio do dono.
2. Ou, no mesmo aplicativo do vendedor de teste, **ativar as credenciais de produção** (preencher ramo e site).
   Assim o `APP_USR-` deixa de ser bloqueado.
3. Opcional: preencher `MP_TEST_SELLER_PUBLIC_KEY` / `MP_TEST_SELLER_ACCESS_TOKEN` e `MP_SANDBOX_BUYER_EMAIL` (ver H4).

Depois de trocar o par, rode de novo a suíte com o comando da seção "Como rodar". Todos os testes de pagamento devem
ficar verdes, e as linhas `[H3]`, `[H4]` e `[H5]` do console completam este relatório.

## Credenciais usadas e caminhos (a) e (b)

| Caminho | Situação |
|---|---|
| (a) `MP_SANDBOX_PUBLIC_KEY` / `MP_SANDBOX_ACCESS_TOKEN` | Testado. Ambos começam com `APP_USR-`. A conta é de teste: `tags` contém `test_user`. O user id do token é **igual** a `MP_TEST_SELLER_USER_ID` e o nickname é igual a `MP_TEST_SELLER_USERNAME`. Portanto este par **já são as credenciais de produção do vendedor de teste**, ou seja, na prática o caminho (b). Resultado: tokenização OK, pagamentos 401 `Unauthorized use of live credentials`. |
| (b) `MP_TEST_SELLER_PUBLIC_KEY` / `MP_TEST_SELLER_ACCESS_TOKEN` | **Não testado: chaves não fornecidas** (vazias no arquivo). Veja acima: o par (a) atual é, de fato, deste tipo. |
| Credenciais `TEST-` | Não testado: não fornecidas. O aplicativo do vendedor de teste tem uma (campo `test_access_token` de `GET /applications/{id}`, prefixo `TEST`). Não foi usada por não ter vindo do dono. |
| `MP_PROD_*` | Não usadas para criar nada. Só houve uma comparação **local** de `MP_PROD_CLIENT_ID` com o `test_data.client_id` da conta de teste (são iguais: o usuário de teste foi criado pelo aplicativo real da Raykookie). O `client_id` do token `MP_SANDBOX_ACCESS_TOKEN` é de outro aplicativo. |

Comparações feitas localmente, sem exibir valores:

- id de `/users/me` do token `MP_SANDBOX_ACCESS_TOKEN` igual a `MP_TEST_SELLER_USER_ID`: **sim**
- o access token termina com `-<user id>`: **sim**
- `client_id` do token igual a `MP_PROD_CLIENT_ID`: **não**. Igual a `test_data.client_id`: **não**.
- `test_data.client_id` igual a `MP_PROD_CLIENT_ID`: **sim**
- token de cartão criado com `MP_SANDBOX_PUBLIC_KEY`: `live_mode: true`

`GET /applications/{client_id do token}` (com o próprio token), só os campos não pessoais:
`sandbox_mode: true`, `active: true`, `certification_status: "not_certified"`, `scopes` incluindo
`urn:mp:online:payments/read-write`, `access_token` com prefixo `APP_USR` (igual ao do arquivo) e
`test_access_token` com prefixo `TEST`.

O que esse par consegue e não consegue fazer:

| Chamada | Resultado |
|---|---|
| `GET /users/me` | 200 |
| `GET /v1/payments/search` | 200 |
| `GET /v1/payment_methods` | 200 |
| `POST /checkout/preferences` | 201 |
| `POST /v1/card_tokens?public_key=…` | 201 |
| `GET /v1/card_tokens/{id}` (Bearer) | 200 |
| `GET /users/me/mercadopago_account/balance` | 403 `forbidden` |
| `POST /v1/payments` com Pix (2 e-mails de pagador) | **401** `Unauthorized use of live credentials` (code 7) |
| `POST /v1/payments` com cartão (todas as variações) | **401** `Unauthorized use of live credentials` (code 7) |

### `/users/me` real (mascarado)

O campo que marca a conta de teste **existe como o plano supunha**: `tags` contém `"test_user"`. Há também um
objeto `test_data` com `test_user: true`. Forma real, resumida, com os valores pessoais trocados por `***`/`0`:

```json
{
  "id": 0,
  "nickname": "***",
  "email": "***@testuser.com",
  "country_id": "BR",
  "site_id": "MLB",
  "user_type": "normal",
  "tags": ["user_product_seller", "test_user", "normal"],
  "identification": { "number": "***", "type": "***" },
  "status": { "site_status": "active", "confirmed_email": false, "mercadopago_tc_accepted": true, "...": "..." },
  "test_data": {
    "id": 0, "nickname": "***", "site_status": "active",
    "test_user": true, "external": true, "is_custom_test_user": false,
    "client_id": 0, "user_owner": 0
  },
  "...": "seller_reputation, buyer_reputation, company, credit, address, phone (todos mascarados)"
}
```

O e-mail da conta de teste segue o padrão `test_user_<dígitos do nickname>@testuser.com`, conferido na conta do
vendedor.

## Cartões de teste: o que a API real diz

`GET /v1/payment_methods/installments?bin=<6 dígitos>&amount=10&public_key=MP_SANDBOX_PUBLIC_KEY`:

| Cartão | bin | HTTP | `payment_method_id` | `payment_type_id` | `issuer.id` |
|---|---|---|---|---|---|
| Mastercard **5031 4332 1540 6351** (tabela do plano) | 503143 | **404** `payment method not found` | — | — | — |
| Mastercard **5031 7557 3453 0604** | 503175 | 200 | `master` | `credit_card` | 24 |
| Mastercard 5480 8328 0103 3311 (teste antigo) | 548083 | 200 | `master` | `credit_card` | 12749 |
| Visa 4235 6477 2802 5682 | 423564 | 200 | `visa` | `credit_card` | 25 |
| American Express 3753 651535 56885 | 375365 | 200 | `amex` | `credit_card` | 18 |
| Elo 5067 7667 8388 8311 ("débito" no plano) | 506776 | 200 | `elo` | **`prepaid_card`** | 687 |

Divergências com a tabela "Fidelidade dos sistemas externos" do plano:

- A Mastercard `5031 4332 1540 6351` **não existe** para a API. A suíte usa a `5031 7557 3453 0604`.
- A Elo de teste volta como `prepaid_card`, não como débito.

Tokenização (`POST /v1/card_tokens`) dos 4 cartões: 201, com `first_six_digits`/`last_four_digits` corretos,
`status: "active"` e `live_mode: true`. CVV com 5 dígitos é recusado na tokenização (4xx).
`GET /v1/card_tokens/{id}` **funciona no servidor** com o access token (200, devolve `public_key`, primeiros e
últimos dígitos, validade e `cardholder`). Ele também funciona sem Bearer, com `?public_key=`.

## Nomes mágicos do titular

**Não medidos.** Os 8 casos (`APRO`, `OTHE`, `CONT`, `CALL`, `FUND`, `SECU`, `EXPI`, `FORM`) tokenizam (201), mas
o pagamento volta 401 `Unauthorized use of live credentials` antes de o nome mágico ser avaliado. A suíte afirma o
resultado documentado (tabela do plano) e vai validá-lo assim que o par de credenciais for trocado.

## Asserções não validadas ao vivo

Os testes abaixo afirmam o resultado **documentado**, mas esse resultado **nunca foi observado**: em 2026-10-08
todos voltaram 401 antes de o Mercado Pago avaliar o pagamento. No código, cada um tem o comentário
`não validado ao vivo (401 em 2026-10-08)`. **Na primeira execução verde (com credenciais que funcionem), conciliar
cada linha com o que a API devolver**: ajustar o teste ao comportamento real e registrar a divergência aqui, se houver.
Isso precisa acontecer **antes de a Tarefa 5.2 gravar respostas ou depender destes resultados**.

| Teste | Asserção (documentada, não observada) | Fonte / confiança |
|---|---|---|
| Nomes mágicos ×8 (`APRO`, `OTHE`, `CONT`, `CALL`, `FUND`, `SECU`, `EXPI`, `FORM`) | HTTP 201 + `status`/`status_detail` da tabela "Nomes mágicos" do plano | Documentação "Realizar compra teste" |
| H3 `COM issuer_id` ×4 (Mastercard, Visa, Amex, Elo) | 201, `approved`, `accredited` | Documentação de envio de pagamento |
| H3 `SEM issuer_id` ×4 | 201, `approved`, `accredited` | A referência da API trata `issuer_id` como opcional. Se der recusa ou erro aqui e o "COM" aprovar, **H3 está confirmada** |
| H4 e-mail do comprador de teste | 201, `approved`, `accredited` | Documentação de contas de teste |
| H4 e-mail do **próprio vendedor** (pagador = recebedor) | HTTP **400** com mensagem `Invalid users involved` | Erro conhecido do Mercado Pago para pagador = recebedor. **Baixa confiança no texto exato**: conferir mensagem e `cause.code` |
| H4 e-mail externo (`teste@raykookie.com.br`) | 201, `approved`, `accredited` | A documentação não restringe o e-mail do pagador via API. Confiança média |
| H5 Visa enviada como `master` | HTTP **400** com `message: "diff_param_bins"` | Erro conhecido para bin do token diferente do `payment_method_id`. **Baixa confiança no texto exato** |
| `createCardPayment` (Node, sem `issuer_id`) | `approved`, `accredited` | Igual a H3 `SEM issuer_id` |

Cada teste cria **um** pagamento, para que o resultado de um lado da comparação (com × sem `issuer_id`) não esconda o
outro. Nenhum deles aceita qualquer resposta abaixo de 500 como sucesso.

## H1 — `MP_PUBLIC_KEY` ausente em produção

**Veredito: confirmada.**

- `curl https://raykookie-api.kntap.com.br/api/payment-config` devolveu `{"cardEnabled":false,"publicKey":null}` em 2026-10-08.
- `docker inspect raykookie-api` (só os nomes): `MP_ACCESS_TOKEN`, `MP_CLIENT_ID`, `MP_CLIENT_SECRET`,
  `MP_WEBHOOK_SECRET`. **Não há `MP_PUBLIC_KEY`.**
- No código, `mpCardConfigured()` exige `MP_ACCESS_TOKEN` e `MP_PUBLIC_KEY`. Como ele é falso, `payment-config`
  manda `cardEnabled:false`. `NewOrderForm.tsx`, `PayLink.tsx`, `CardRetry.tsx` e `new_order_screen.dart` escondem o
  cartão, e `payWithCard` responde 503.

## H2 — credenciais "de produção" com cartões de teste

**Veredito: confirmada, com a causa reformulada.**

- O erro do teste antigo (`Unauthorized use of live credentials`) **se repete** com o par atual, que é de uma
  **conta de teste** (`tags: test_user`). A hipótese "o token não é de conta de teste" não explica o erro.
- A causa medida: o access token é `APP_USR-` de um aplicativo da conta de teste com `sandbox_mode: true` e não
  certificado. O token de cartão sai com `live_mode: true`, e o Mercado Pago bloqueia **qualquer** criação de
  pagamento com esse par, inclusive Pix. Isso descarta causas ligadas a cartão, bandeira, emissor ou e-mail.
- O teste antigo escondia isso com um `try/catch` que transformava o 401 em sucesso. A nova suíte falha com
  uma mensagem que explica o que trocar.

## H3 — `issuer_id` não enviado

**Veredito: não testável com as credenciais atuais.** O problema existe no código.

- `createCardPayment` (`backend/lib/mercadopago.ts`) não envia `issuer_id`. `CardFields.tsx` e `card_fields.dart`
  também não guardam o `issuer.id` que `/installments` devolve, então o servidor não teria o valor para mandar.
- A comparação com e sem `issuer_id` (4 cartões, `APRO`) está na suíte (`describe('H3: issuer_id')`), em testes
  separados (um pagamento cada), e também no caso que chama o `createCardPayment` real. Hoje todos voltam 401 antes
  de avaliar o emissor.
- Emissores reais por bin, para quando der para medir: Mastercard 24, Visa 25, Amex 18, Elo 687.

## H4 — e-mail do pagador

**Veredito: não testável com as credenciais atuais.**

Tentativas de `APRO` (Mastercard 5031 7557…, com `issuer_id` 24). O erro foi o mesmo em todas:

| E-mail do pagador | Resultado |
|---|---|
| `test@testuser.com` (documentado) | 401 `Unauthorized use of live credentials` (code 7) |
| `test_user_<dígitos de MP_TEST_BUYER_USERNAME>@testuser.com` (padrão derivado do vendedor) | 401 `Unauthorized use of live credentials` (code 7) |
| `teste@raykookie.com.br` (o do teste antigo) | 401 `Unauthorized use of live credentials` (code 7) |
| e-mail do próprio vendedor (pagador = recebedor), pela suíte | 401 `Unauthorized use of live credentials` (code 7) |

O 401 vem antes da validação do pagador, então nada se conclui sobre o e-mail. A suíte lê `MP_SANDBOX_BUYER_EMAIL`,
que está vazio, e usa `test@testuser.com` como reserva. Recomendação: o dono preenche `MP_SANDBOX_BUYER_EMAIL` com o
e-mail que o painel mostra para o comprador de teste, provavelmente `test_user_<dígitos>@testuser.com`.

## H5 — `payment_method_id` errado

**Veredito: descartada** pela parte que dá para verificar. O efeito de um id errado no pagamento não foi testável.

- `CardFields.tsx` (site) chama `mp.getInstallments({ bin })` e usa `res[0].payment_method_id`.
  `card_fields.dart` (app) chama `GET /v1/payment_methods/installments?bin=<6 dígitos>&public_key=…` e usa
  `first['payment_method_id']`. As duas telas mandam ao servidor o id que a própria API devolve.
- A API real devolve, para os 4 cartões, exatamente `master`, `visa`, `amex` e `elo` (tabela acima, coberta por
  testes verdes). Com bins válidos, o site e o app não podem mandar um id errado.
- Risco que sobra: se o bin não for reconhecido (como a Mastercard `5031 4332…`), `/installments` dá 404, as telas
  deixam o id vazio e mostram "Não reconhecemos a bandeira". O cliente fica bloqueado, mas nenhum id errado é enviado.
- O teste "Visa enviada como `master`" está na suíte. Hoje ele também volta 401.

## H6 — chave pública e access token de ambientes diferentes

**Veredito: descartada no código.** Na API, a combinação errada não foi testável.

- O site e o app não têm chave embutida: os dois leem a chave pública de `GET /api/payment-config`. Esse endpoint
  devolve `MP_PUBLIC_KEY` do **mesmo** `.env` que tem o `MP_ACCESS_TOKEN` usado no pagamento. A mistura só acontece
  se alguém configurar o `.env` com chaves de contas diferentes. Hoje nem isso é possível, porque falta a chave
  pública (H1).
- O token de cartão carrega a chave pública que o gerou (`GET /v1/card_tokens/{id}` → `public_key`, conferido na
  suíte). Isso dá ao servidor um jeito barato de detectar a mistura.
- Testar a combinação errada pede um segundo par: o de produção é proibido para criar token ou pagamento, e
  `MP_TEST_SELLER_*` está vazio.

## Recomendações

### Node (não aplicadas nesta tarefa: o passo 3 não foi executado, por decisão do controlador)

1. **H1 (resolve a produção):** adicionar `MP_PUBLIC_KEY` (valor de `MP_PROD_PUBLIC_KEY`) ao `.env` do container
   `raykookie-api` e reiniciar. Conferir que `/api/payment-config` passa a trazer `cardEnabled:true`. Depois o dono faz
   um pagamento real de valor baixo e o devolve pelo painel.
2. **H3:** passar `issuer_id` de ponta a ponta. `CardFields.tsx` e `card_fields.dart` guardam `res[0].issuer.id`; o
   corpo do pedido ganha `card_issuer_id`; `createCardPayment` envia `issuer_id`. O formato de pagamento que o
   Mercado Pago documenta inclui esse campo. A medição de que ele é necessário depende das credenciais novas.
3. **Credenciais de teste:** trocar `MP_SANDBOX_*` como descrito em "Ação do dono" e rodar a suíte de novo.
4. **Tabela de cartões do plano:** trocar a Mastercard por `5031 7557 3453 0604` e marcar a Elo como pré-paga.
5. Opcional: em `createCardPayment`, logar o `status`/`cause.code` do 401 com uma mensagem clara.
   Hoje o erro vira um 502 genérico ("Não conseguimos falar com o Mercado Pago").

### Adaptador Java (Tarefa 3.1/5.x)

1. `MercadoPagoCardGateway` envia sempre `token`, `transaction_amount`, `installments`, `payment_method_id`,
   **`issuer_id`**, `description`, `external_reference`, `notification_url`, `payer.email`, `payer.first_name`,
   `payer.identification`, e `X-Idempotency-Key` = UUID da tentativa.
2. O DTO de entrada do cartão exige `paymentMethodId` e `issuerId` (o cliente manda o que `/installments` devolveu).
3. Na inicialização (ou no health), `GET /users/me` com o token e checagem do perfil. Em produção, recusar subir se
   `tags` contiver `test_user`. No perfil `sandbox`, recusar se **não** contiver.
4. Antes de cobrar, `GET /v1/card_tokens/{id}`: conferir que `public_key` do token é a configurada (H6). Se não for,
   devolver um 400 claro, sem chamar `/v1/payments`.
5. Mapear 401 `cause.code = 7` ("Unauthorized use of live credentials") para um erro de configuração com alerta à
   dona, e não para "tente de novo".
6. `cardEnabled` só fica verdadeiro com access token **e** chave pública presentes e do mesmo `user_id`. A chave
   pública não tem `/users/me`, mas o `public_key` de um token de teste mostra a conta.
7. Gravações da 5.2: usar os cartões e emissores da tabela acima e mascarar `id`, `email` e `public_key`.

## Como rodar

```
set -a; . ~/.raykookie/mercadopago.env; set +a
cd backend && RUN_MP_SANDBOX=1 npx vitest run tests/card-pay.sandbox.test.ts
```

Equivalente, sem `source`:
`RUN_MP_SANDBOX=1 node --env-file=$HOME/.raykookie/mercadopago.env node_modules/vitest/vitest.mjs run tests/card-pay.sandbox.test.ts`
(dentro de `backend/`).

Resultado em 2026-10-08 (depois da revisão que separou os testes): **13 verdes, 21 vermelhos (34)**. Verdes: conta de teste, bandeira/emissor dos 4 cartões,
Mastercard do plano 404, `payment_methods?bin` sem filtro, tokenização dos 4, CVV inválido e `GET card_tokens`.
Vermelhos: todos os testes que criam pagamento (8 nomes mágicos, 8 de H3 com e sem `issuer_id`, 3 de H4, 1 de H5,
1 do `createCardPayment`), sempre com o mesmo 401 `Unauthorized use of live credentials`.
