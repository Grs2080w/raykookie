# Plano: homologação do Mercado Pago (order/payment id de teste) e módulos do Checkout Transparente

Origem: `MERCADO_PAGO_LINKS.md` (arquivo local do dono, não versionado) e os prints `modulos.jpeg` e `opcoes_cartao.jpeg`.
Pesquisa feita em 2026-10-12 nas docs do Mercado Pago (links no fim). O que **não** foi confirmado está marcado como
**[confirmar]**.

## 1. O que o Mercado Pago pede

Na etapa final, em *Suas integrações → app → Dados da integração → Testar a integração*, o painel mede a qualidade da
integração a partir do identificador **de um pagamento feito com credenciais de teste, nos últimos 7 dias**.
Nota mínima 73 (recomendado 100), e todas as **ações obrigatórias** precisam estar resolvidas. Os eixos avaliados são
experiência do comprador, conciliação financeira, aprovação de pagamentos, escalabilidade e segurança.

### Por que o ID da consulta ao banco foi rejeitado

A consulta `SELECT … paid_mp_id FROM shop_orders …` devolve ids de pagamentos **de produção** (Pix e cartão reais).
Isso não serve, por dois motivos:

1. A medição pede um pagamento feito com credenciais **de teste**. Os pedidos do banco de produção são feitos com
   `APP_USR-` de produção.
2. O formato pode não ser o que o campo espera. As páginas novas (Checkout Pro com Orders API) pedem o **Order ID**; a
   página antiga pede o **payment ID**. O nosso backend usa a **API Payments** (`POST /v1/payments`), então só temos
   `payment id` (numérico). Se o campo do painel espera Order ID (a API Orders devolve ids no estilo `ORD…`) **[confirmar]**,
   o erro "o número não bate com o formato" é exatamente esse. A página oficial não informa o formato.

### Estado das credenciais de teste hoje

`docs/mercadopago-cartao-diagnostico.md` (08/10): o par `MP_SANDBOX_*` do `~/.raykookie/mercadopago.env` é um `APP_USR-`
do vendedor de teste. Todo `POST /v1/payments` com ele volta `401 Unauthorized use of live credentials`.
A documentação oficial manda usar **as credenciais de teste do seu usuário de produção**: em *Detalhes da aplicação →
Credenciais → Credenciais de teste* (prefixo `TEST-`). Esse par nunca foi usado.

## 2. Resultado da avaliação dos prints

| Item do print | Marcado no painel | No app hoje | Decisão |
|---|---|---|---|
| Cartão de crédito (Visa, Mastercard, Amex, Hipercard…) | sim | sim: o BIN define a bandeira e as parcelas (`CardFields.tsx`, `card_fields.dart`) | manter; **mostrar as bandeiras aceitas** (fase 3) |
| Cartão de débito (Elo, virtual CAIXA) | sim | sim: escolha crédito/débito (`c093243`) | manter; mostrar bandeiras |
| Cartão pré-pago (Visa, Mastercard) | **não** | não tratado | decidir com o dono; se habilitar, testar um pré-pago e a exibição **[confirmar]** |
| Brand Brick | não | não existe | opcional; basta a faixa de bandeiras da fase 3 |
| Status Screen Brick | não | não existe: o app tem tela de status própria (webhook + consulta) | fase 4 (site); no app, manter a tela nativa |
| Payment Brick: Revise e Confirme | **sim** | **não usamos Bricks**; o passo de revisão é nosso | **inconsistência**: desmarcar ou implementar (fase 6) |
| 3DS | não | não existe (`three_d_secure_mode` não aparece no código) | fase 5, depois da aprovação |
| Gestão de dados de cartões e clientes | **sim** | não usamos `/v1/customers` nem cartão salvo | **inconsistência**: desmarcar, salvo se for implementar cartão salvo **[confirmar]** |

Esses itens são a declaração do painel: marque só o que o app realmente usa, para a avaliação não cobrar o que não existe.

Já atendido (conferido no código): `notification_url` e webhook, `external_reference` (id do pedido ou do link),
`statement_descriptor`, `additional_info` com item e comprador, device ID (`MP_DEVICE_SESSION_ID`, header
`X-meli-session-id`), MercadoPago.js v2 com campos seguros, validação de `MP_PUBLIC_KEY` na subida.

## 3. Fases

### Fase 0: confirmar o que o painel pede (dono, 5 min)

1. Abrir *Testar a integração* do app e enviar um print da tela de **Informar dados sobre a integração** e do campo
   do ID (placeholder/ajuda do formato).
2. Decidir pelo resultado:
   - **A**: campo pede *payment ID* (numérico) → fase 1 resolve.
   - **B**: campo pede *Order ID* → fase 1 + fase 1b.

### Fase 1: gerar um pagamento de teste isolado

Não usar o banco nem o container de produção.

1. **Dono:** copiar as **Credenciais de teste** (`TEST-…`: chave pública e access token) do app de produção para
   `~/.raykookie/mercadopago.env`, em variáveis novas `MP_TEST_PUBLIC_KEY` e `MP_TEST_ACCESS_TOKEN`.
2. **Agente:** adaptar `backend/tests/card-pay.sandbox.test.ts` (já tokeniza, cria o pagamento e imprime o resultado)
   para ler essas duas variáveis, **ou** subir uma API Java descartável (porta própria, banco Postgres de teste do
   Testcontainers/local, `MP_ACCESS_TOKEN`/`MP_PUBLIC_KEY` = `TEST-`) e pagar pelo site ou app apontado para ela.
   Conferir que `MercadoPagoEnvironmentCheck` aceita o par `TEST-` (ele chama `GET /users/me` e `/v1/payment_methods`).
3. Pagar com os dados oficiais de teste:
   - Mastercard crédito `5480 8328 0103 3311`, CVV `123`, validade `11/30` (também Visa `4235 6477 2802 5682` e
     Amex `3753 651535 56885`, CVV `1234`); débito Elo `5067 7667 8388 8311`.
   - Nome do titular **`APRO`** (aprovado); CPF `12345678909`.
   - **E-mail do comprador diferente** do e-mail da conta Mercado Pago.
4. Anotar o `id` do pagamento da resposta (e o `external_reference`). **Fazer a medição no mesmo dia** (janela de 7 dias).
5. Colar o id em *Testar a integração → Iniciar medição → Medir qualidade*.

Critério de pronto: o painel aceita o id e mostra a nota e a lista de ações.

### Fase 1b: só se o campo exigir Order ID

A API Orders é a recomendada para integrações novas; a Payments segue funcionando, mas sem novidades. Criar um
adaptador `MercadoPagoOrdersClient` atrás da porta `PaymentGateway` (`backend-java/modules/payments`), com
`POST /v1/orders` (cartão e Pix), consulta e reembolso, escolhido por uma variável (`MP_API=orders|payments`).
Em teste, usar o adaptador novo; em produção, só migrar depois de aprovada a homologação e com os testes de contrato
(`MercadoPagoFake`) atualizados. É a fase mais cara: só fazer se a fase 0 confirmar o caso B.
**[confirmar]** na doc da API Orders o formato exato do id e o corpo do cartão antes de estimar.

### Fase 2: corrigir as ações obrigatórias

Rodar a medição, ler a lista em *Ir para o detalhe*, e tratar cada item obrigatório. Os mais prováveis (pelas docs):
webhook configurado e `external_reference` (já atendidos) e, nas recomendadas, mais dados do comprador. Medir de novo.

### Fase 3: mostrar as bandeiras aceitas

- Backend: acrescentar a `GET /api/payment-config` a lista de meios ativos (`GET /v1/payment_methods` com a chave
  pública, filtrando `credit_card`/`debit_card`/`prepaid_card` e `status = active`, com `secure_thumbnail`), em cache.
- Site (`CardFields.tsx`) e app (`card_fields.dart`): faixa de logos acima do número do cartão, separada em crédito e
  débito. Falha da consulta não bloqueia o pagamento.
- Testes: contrato do endpoint (`payment-config.json`) e widget/teste de componente.

### Fase 4: Status Screen Brick (site)

Na página do pedido, depois do pagamento com cartão, renderizar o brick com o `paymentId` (SDK v2,
`bricksBuilder.create('statusScreen', …)`). Ele mostra o resumo, o status e confirmações de 3DS 2.0. No app Flutter,
manter a tela nativa (não há brick nativo; WebView só se o dono quiser). **[confirmar]** na doc de renderização os
parâmetros e se exige o `paymentId` de um pagamento criado pelo nosso backend (é o caso).

### Fase 5: 3DS 2.0 (depois da aprovação)

Enviar `three_d_secure_mode: "optional"` no `POST /v1/payments`, tratar `status = pending` com
`status_detail = pending_challenge` (abrir o desafio em iframe/WebView e esperar o webhook) e usar cartões de teste
de 3DS. Aumenta a aprovação e a segurança. **[confirmar]** a página "3DS 2.0 com Checkout Transparente" antes de
implementar; hoje o fluxo do app assume cobrança sem desafio.

### Fase 6: alinhar a declaração do painel

Desmarcar o que não usamos (Payment Brick, gestão de cartões e clientes) ou marcar depois de implementar. Decidir o
pré-pago. Salvar e medir de novo.

### Fase 7: pós-aprovação

Manter `MP_ACCESS_TOKEN`/`MP_PUBLIC_KEY` de produção como estão. Guardar o procedimento da fase 1 em
`docs/mercadopago-cartao-diagnostico.md`. Apagar os temporários citados no plano de 07/10 (`~/.claude/jobs/…/prod_env_new.txt`
e `mp_probe3.py`) com a aprovação do dono.

## 4. Decisões do dono

1. O que o campo do painel pede (fase 0).
2. Habilitar cartão pré-pago?
3. Fazer a fase 5 (3DS) já, ou só depois da aprovação? (recomendado: depois.)
4. Fase 4 também no app via WebView, ou só no site?

## 5. Riscos

- Credenciais `TEST-` ainda não testadas: se também voltarem 401, o painel do app precisa de ramo e site preenchidos
  para liberar as credenciais (opção 2 do diagnóstico).
- A janela de 7 dias: gerar o pagamento e medir no mesmo dia.
- Pagamentos de teste não devem tocar o banco de produção (e-mails e pedidos falsos ficariam na loja real).

## Fontes

- Compra de teste: https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/integration-test/make-test-purchase
- Cartões de teste: https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/additional-content/your-integrations/test/cards
- Medir a qualidade (Orders): https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-orders/integration-quality
- Medir a qualidade (Payments): https://www.mercadopago.com.br/developers/en/docs/integration-quality.md
- Status Screen Brick: https://www.mercadopago.com.br/developers/pt/docs/checkout-bricks/status-screen-brick/introduction
