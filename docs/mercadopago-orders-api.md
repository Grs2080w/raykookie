# API Orders do Mercado Pago: como ligar, testar e pegar o Order ID da homologação

Escrito em 2026-10-12, depois de testar a API Orders no sandbox. O que é fato verificado está dito como tal; o que
ficou sem confirmar está marcado.

## O que mudou no sistema

- **`MercadoPagoOrdersClient`** (`backend-java/modules/payments/.../adapter/out/mercadopago/`): Pix, cartão (crédito,
  débito, pré-pago), 3DS, consulta, cancelamento, devolução total e parcial, e busca por `external_reference`.
- **`RoutingPaymentGateway`**: cobranças novas vão para a API de `MP_API` (`payments` padrão, `orders`); o resto segue
  o **formato do id** (`ORD…` → Orders; numérico → Payments). Em `orders`, o "id do pagamento" guardado no pedido
  (`mp_payment_id`, `paid_mp_id`) é o **id do pedido do Mercado Pago**.
- **Webhook `order`** processa como o `payment` (ver `docs/API.md`).
- **3DS**: `MP_3DS=on_fraud_risk` (padrão) pede o desafio quando o Mercado Pago julga arriscado; o site e o app abrem o
  desafio (iframe / WebView) e conferem com `GET …/cartao`.
- **Bandeiras aceitas** e **pré-pago**: o site e o app mostram os meios de cartão ativos da conta e mandam o tipo do
  cartão (`card_payment_type_id`).

Nada disso muda a produção sozinho: com `MP_API` ausente, o sistema continua na API Payments.

## Fatos verificados no sandbox (2026-10-12)

- A API Orders **aceita o par `APP_USR-` do vendedor de teste** (`MP_SANDBOX_*` em `~/.raykookie/mercadopago.env`),
  o que a API Payments recusava (`401 Unauthorized use of live credentials`). Não precisa de credencial `TEST-`.
- O e-mail do comprador no sandbox precisa terminar em **`@testuser.com`**.
- Pedidos de teste têm id **`ORDTST…`** (produção: `ORD…`). Pagamentos: `PAY…`. Devoluções: `REF…`.
- **Débito e pré-pago não aceitam `installments`.** O cartão Elo de teste é detectado como **pré-pago** pelo BIN.
- **Débito exige o id do débito (`debelo`)**, o mesmo que o BIN devolve; não se tira o prefixo `deb` nem se usa só a
  bandeira (`elo` + `debit_card` dá `400 property_value`). No sandbox, o vendedor de teste responde
  `payment_method_not_allowed_for_collector` para `debelo`: **o débito não é testável nessa conta de teste** (precisa
  habilitar o débito no vendedor de teste). O que está verificado é só que o id e o tipo enviados são os aceitos.
- **Cancelar exige `X-Idempotency-Key`** (`400 empty_required_header` sem ela). O adaptador manda `rayka-cancel-<id>`.
- **Devolução logo depois da compra** pode voltar `422 Post processing rejected the operation` ou
  `409 post_processing_operation_pending` (o pedido ainda está sendo processado, `automatic_async`). É transitório e
  **repetir é seguro**: a tentativa que deu 422 deixa só um registro de devolução com `status: failed` (sem devolver
  dinheiro) e a seguinte cria a `processed`; o pedido mostra as duas, mas só uma devolveu. O adaptador devolve a
  processada, e `refunded_amount` só soma as processadas.
- **Recusa vem como HTTP 402**, com o pedido em `data` (`status: failed`, motivo em `status_detail`, ex.:
  `insufficient_amount`). O adaptador traduz para os `cc_rejected_*` que o sistema já explica ao cliente.
- **3DS:** com `config.online.transaction_security` (`on_fraud_risk` + `liability_shift: required`) o pedido volta
  `action_required / pending_challenge` com o endereço do desafio em
  `transactions.payments[0].payment_method.transaction_security.url`. O desafio vale 40 minutos; depois o pedido vira
  `canceled / expired`.
- **Pix** (`payment_method: {id: pix, type: bank_transfer}`): volta `action_required / waiting_transfer` com `qr_code`,
  `qr_code_base64` e `ticket_url`. A validade vai em `transactions.payments[].expiration_time` (ISO 8601, mínimo 30 min).
- Cancelar (`POST /v1/orders/{id}/cancel`), devolver tudo (`POST …/refund` sem corpo) e devolver parte
  (`{"transactions":[{"id":"PAY…","amount":"3.00"}]}`) funcionam; a devolução vem em `transactions.refunds[]`.
- Busca por referência: `GET /v1/orders?external_reference=…&begin_date=…&end_date=…` (o período é obrigatório).

## Teste de integração contra o sandbox real

`MercadoPagoOrdersSandboxLive` exercita o **nosso adaptador** na API real: crédito (Mastercard e Visa), pré-pago, débito,
Pix (com busca por referência e cancelamento), 3DS, recusa por saldo, devolução parcial e total, e as bandeiras da
conta. O nome não termina em `IT` de propósito: o `verify` e o CI (que não aceita teste pulado) nunca o incluem. Ele só
roda quando chamado pelo nome, com `RUN_MP_SANDBOX=1`:

```
RUN_MP_SANDBOX=1 MP_SANDBOX_ACCESS_TOKEN=… MP_SANDBOX_PUBLIC_KEY=… \
  ./mvnw -pl modules/payments -am verify -Dit.test=MercadoPagoOrdersSandboxLive
```

Os ids dos pedidos criados vão para `backend-java/modules/payments/target/mp-sandbox-orders.txt`. Resultado de
2026-10-12: 8 casos, 0 falhas, 1 pulado (débito, pela razão acima). Esse teste achou dois erros que os testes com Mercado
Pago falso não pegavam: o prefixo `deb` tirado do débito e o cancelamento sem a chave de idempotência.

## Como conseguir o Order ID para a homologação

O painel pede o id de um pedido feito com credenciais **de teste** nos últimos **7 dias**
(*Suas integrações → app → Testar a integração → Iniciar medição*).

1. Crie um pedido de teste que passe pelo mesmo corpo que o sistema envia (itens, comprador, `external_reference`,
   descrição na fatura). Os scripts usados nesta investigação criam pedidos assim direto no sandbox; para repetir pelo
   próprio sistema, suba uma API local com `MP_API=orders`, `MP_ACCESS_TOKEN`/`MP_PUBLIC_KEY` = par `MP_SANDBOX_*` e
   `PAYMENTS_CARD_ENABLED=true`, e pague pelo site ou pelo app.
2. Cartões de teste: Mastercard `5480 8328 0103 3311` e Visa `4235 6477 2802 5682` (CVV 123, validade 11/30),
   Amex `3753 651535 56885` (CVV 1234); titular **`APRO`** aprova, `FUND` recusa por saldo. Para o desafio 3DS:
   titular **`APRO-CHOK`** (autenticado) ou `OTHE-CHNO` (não autenticado). CPF `12345678909`.
3. Cole o `id` do pedido (`ORDTST…`) no painel e meça no mesmo dia.

## Para ligar em produção (depois de aprovada a homologação)

1. `MP_API=orders` no `backend/.env.docker` (e, se quiser desligar o 3DS, `MP_3DS=never`).
2. Recriar o container: `bash infra/ci/run-api.sh raykookie-api-java:latest` (um `docker restart` não relê o arquivo).
3. No painel do Mercado Pago, o webhook de produção precisa ter o evento **Order** (além de **Pagamentos**).
4. No painel, marque o **cartão pré-pago** em *Meios de pagamento* (a caixa estava desmarcada) e o **3DS**.

## Status Screen Brick: não implementado (decisão pendente)

A documentação do Brick só aceita `paymentId` da **API Payments** (id numérico). Na API Orders o pagamento tem id
`PAY…` e o pedido `ORD…`; a resposta de cartão não traz o id numérico (no Pix ele aparece só dentro de `ticket_url`).
Não consegui testar o Brick no navegador (extensão indisponível), então **não sei se ele aceita o id da API Orders**.
A chave `MP_STATUS_SCREEN` (e `statusScreenBrick` no `payment-config`) já existe para ligar/desligar sem novo APK.
Enquanto isso, o site e o app mostram o status do pedido na tela nativa. Se o Brick não aceitar o id da API Orders, ele só
serviria para pagamentos feitos com `MP_API=payments`.
