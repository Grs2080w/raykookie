# Pagamento com cartão (Mercado Pago) — Design

## Objetivo

Trazer "cartão" de volta como forma de pagamento real no checkout (web e app), além do Pix e do dinheiro
já existentes, cobrando de fato via Mercado Pago — e também no link de pagamento de pedidos registrados à
mão. Sem juros: parcelamento disponível só em pedidos acima de R$150, loja absorve a taxa do Mercado Pago.
Dados do cartão nunca tocam nosso backend. No app Flutter, campos nativos com dicas de autofill (sem botão
de carteira dedicado — ver "Google Pay" abaixo).

## Decisões já fechadas na conversa com a confeiteira

- Parcelamento sem juros, só acima de R$150 (ela decide o limite; eu fixo esse valor como constante
  compartilhada, não configurável por enquanto).
- Cartão vale tanto no checkout normal (cliente faz pedido e paga na hora) quanto no link de pagamento
  (pedido manual + link do WhatsApp).
- Web: campos próprios (nossa cara) usando "Secure Fields" do Mercado Pago (iframes minúsculos só para
  número/validade/CVV — o resto do formulário é nosso HTML/CSS).
- App Flutter: sem SDK oficial do Mercado Pago para Flutter. Tokenização direta via chamada HTTPS à API
  pública do Mercado Pago (`/v1/card_tokens`, só com a chave pública), a partir de campos 100% nativos do
  Flutter. Nenhum WebView, nenhum app externo abre.
- **Google Pay / Samsung Wallet**: confirmado por busca na documentação oficial do Google e do Mercado
  Pago que o Mercado Pago **não é um gateway parceiro do Google Pay** (não aparece na lista de gateways
  suportados para tokenização `PAYMENT_GATEWAY`). Não existe hoje um caminho realista de "pagar com Google
  Pay processando pelo Mercado Pago" sem o Mercado Pago aderir a esse programa — fora do nosso controle.
  Por isso, implementamos apenas autofill nativo (dicas `AutofillHints.creditCard*` no Flutter,
  `autocomplete` no web) — o Android/Google Password Manager pode sugerir um cartão salvo para preencher o
  formulário, mas nenhum botão "Pagar com Google Pay" de verdade. Se mais pra frente a confeiteira
  confirmar que o Mercado Pago passou a suportar isso (ou quiser integração direta com o Google, que exige
  aprovação de compliance do próprio Google), reavaliamos.

## O que já existe e será reaproveitado

- `backend/lib/mercadopago.ts`: cliente HTTP do Mercado Pago (`mp()`), confere assinatura do webhook,
  cria/cancela pagamento, cria devolução. Ganha duas funções novas (cartão) sem tocar nas existentes (Pix).
- `payment_method` em `shop_orders`/pedidos manuais já aceita `'cartao'` no banco (`CHECK` constraint) —
  só não é mais oferecido no checkout hoje. Reaproveitamos a coluna como está.
- `paid_at`, `paid_mp_id`, `mp_payment_ids` em `shop_orders` e `order_pay_links` já são agnósticos quanto à
  forma de pagamento — cartão usa exatamente os mesmos campos que o Pix usa hoje.
- Webhook (`/api/webhooks/mercadopago`) já resolve pagamento → pedido via `external_reference` e já trata
  `refunded`/`charged_back` — nenhuma mudança necessária nele. Cartão usa o mesmo `external_reference =
  <id do pedido>` que o Pix usa, então reconciliação de estorno/chargeback funciona sem tocar no webhook.
- `refunds.ts` (devolução) já lida com qualquer pagamento identificado por `paid_mp_id` — devolução de
  cartão funciona pelo mesmo fluxo que devolução de Pix, sem mudança.
- `order_events` já tem `actor='mercadopago'` — reaproveitado para logar aprovação/recusa de cartão.
- `pay-link.ts`: `markLinkPaid`/`promoteInTx` já promovem qualquer pagamento aprovado (id + valor) a pedido
  do app, não importa se veio de Pix ou de cartão — só precisamos gerar o pagamento de cartão e chamar
  `markLinkPaid` com o resultado, do mesmo jeito que o Pix do link já faz hoje.

## Diferença-chave Pix vs. cartão: aprovação é síncrona

Pix nunca falha na hora — ele só existe esperando ser pago (ou nunca ser pago). Cartão aprova ou recusa
*na mesma chamada* à API do Mercado Pago. Isso muda o fluxo de criação do pedido:

1. Pedido é criado **sem pagamento ainda confirmado** (igual Pix hoje): valida o carrinho, calcula o total,
   insere em `shop_orders` com `paid_at = null`.
2. Imediatamente depois, tentamos cobrar o cartão contra esse pedido (`external_reference = id do pedido`).
3. **Aprovado**: marcamos pago na mesma resposta (sem esperar webhook — webhook chega depois e só confirma
   de novo, idempotente pela cláusula `WHERE paid_at IS NULL`).
4. **Recusado**: o pedido continua existindo, sem pagamento — exatamente como um pedido "dinheiro" ou um
   Pix nunca pago hoje. O cliente vê o motivo da recusa e pode **tentar outro cartão no mesmo pedido**, um
   endpoint de retry que reaproveita o padrão já usado por "gerar novo Pix" (`regeneratePix`).
5. **Pendente** (`in_process`, raro em cartão de crédito mas existe): fica como um Pix pendente — o webhook
   confirma depois.

Isso evita duas coisas ruins: (a) nunca cobrar sem o pedido existir primeiro (perderíamos o motivo de ligar
o pagamento ao pedido), e (b) nunca criar um pedido novo a cada tentativa de cartão recusado (o que lotaria
a lista de pedidos da confeiteira de lixo).

## O que muda no banco

Migração nova, duas colunas em `shop_orders` e as mesmas duas em `order_pay_links`:

```sql
ALTER TABLE shop_orders
  ADD COLUMN installments SMALLINT NOT NULL DEFAULT 1 CHECK (installments BETWEEN 1 AND 12),
  ADD COLUMN card_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE order_pay_links
  ADD COLUMN installments SMALLINT NOT NULL DEFAULT 1 CHECK (installments BETWEEN 1 AND 12),
  ADD COLUMN card_attempts INT NOT NULL DEFAULT 0;
```

Não guardamos CPF em lugar nenhum — ele só é repassado ao Mercado Pago no momento da cobrança (exigência
dele para prevenção a fraude), nunca persistido no nosso banco. Isso também simplifica o que a Política de
Privacidade precisa declarar.

## Configuração (o que a confeiteira vai configurar depois)

Uma variável de ambiente nova no backend: `MP_PUBLIC_KEY` (a chave pública do Mercado Pago — different da
`MP_ACCESS_TOKEN`, que é secreta e já existe). `mpCardConfigured()` = `mpConfigured() && !!MP_PUBLIC_KEY`.

A chave pública é servida por um endpoint público novo, `GET /api/payment-config` → `{ cardEnabled: bool,
publicKey: string|null }`. O checkout (web e app) só mostra "Cartão" como opção se `cardEnabled` for
verdadeiro. **Nenhum rebuild do app é necessário** quando a confeiteira configurar a chave — é só a
variável de ambiente do servidor, lida em tempo real (ao contrário do `apiBase`, que é fixado no build do
app via `--dart-define`). Isso também cobre o "Merchant id" que ela disse que ia buscar depois: é
exatamente essa chave pública.

## Backend: novas peças

- `backend/lib/mercadopago.ts` ganha:
  - `mpCardConfigured()`
  - `createCardPayment({ orderId, code, total, email, name, cpf, cardToken, paymentMethodId, installments,
    attempt, reference? })` → `POST /v1/payments` com `token`, `installments`, `payment_method_id`,
    `payer.identification = {type:'CPF', number: cpf}`, `transaction_amount`, `external_reference`,
    `notification_url`. Devolve `{ paymentId, status, statusDetail }`.
  - `cardFailReason(statusDetail)`: mapeia os códigos mais comuns do Mercado Pago (`cc_rejected_bad_filled_security_code`,
    `cc_rejected_bad_filled_date`, `cc_rejected_insufficient_amount`, `cc_rejected_call_for_authorize`,
    `cc_rejected_card_disabled`, `cc_rejected_high_risk`) para frases em PT-BR; outros códigos caem num
    genérico ("Não conseguimos aprovar esse cartão. Tente outro cartão ou Pix.").
- `backend/lib/card-pay.ts` (novo, mesmo formato de `pix.ts`):
  - `payWithCard(orderId, code, total, email, name, cpf, cardToken, paymentMethodId, installments, attempt)`:
    chama `createCardPayment`; se aprovado, faz a mesma atualização que o webhook faz para Pix (marca
    `shop_orders.paid_at`/`paid_mp_id`, `orders.payment='pago'`, `logEvent`, `notifyPaid`/`notifyCustomerPaid`,
    tudo dentro de uma transação com a mesma cláusula de guarda `WHERE paid_at IS NULL`); se recusado,
    incrementa `card_attempts` e devolve o motivo; se pendente, só guarda o `payment_id` em
    `mp_payment_ids` e devolve "aguardando confirmação".
  - `MAX_CARD_ATTEMPTS = 5` (mesma ideia do `MAX_PIX_ATTEMPTS`, evita abuso/teste de cartão roubado contra
    um pedido real).
- `backend/app/api/payment-config/route.ts` (novo, público): `{ cardEnabled, publicKey }`.
- `backend/app/api/cliente/pedidos/route.ts`: branch novo ao lado do Pix — se `payment_method==='cartao'`,
  chama `payWithCard` logo após `createShopOrder` e inclui `card: { approved, reason? }` na resposta.
- `backend/app/api/cliente/pedidos/[id]/cartao/route.ts` (novo): `POST` — retry de cartão no pedido já
  criado (dono = cliente logado, pedido não pago nem cancelado, `card_attempts < MAX_CARD_ATTEMPTS`).
- `backend/lib/pay-link.ts`: `payLinkCard(token, cardToken, paymentMethodId, installments, cpf)` — mesmo
  desenho de `payLinkPix`, mas chama `createCardPayment` com `reference = LINK_REF + linkId`; ao aprovar,
  chama o `markLinkPaid` já existente (mesmo caminho que promove a pedido do app).
- `backend/app/api/pagar/[token]/cartao/route.ts` (novo): expõe `payLinkCard` para a página pública do
  link de pagamento.

## `shared/` (TS) e espelho Dart

- `shared/cpf.ts` (novo): `cleanCpf`, `isValidCpf` (algoritmo padrão de dígito verificador). Espelhado em
  `mobile-common/lib/core/cpf.dart`.
- `shared/shop.ts`: `PAYMENT_METHODS` ganha `{ id:'cartao', label:'Cartão', hint:'Pague com cartão de
  crédito, com segurança.' }` de volta; `CARD_INSTALLMENTS_MIN_AMOUNT = 150`; `parseShopOrder` valida,
  quando `payment_method==='cartao'`: `card_token` (string não vazia), `cpf` (via `isValidCpf`),
  `installments` (inteiro 1–12; só pode ser >1 se `total > CARD_INSTALLMENTS_MIN_AMOUNT`). Espelhado em
  `mobile-common/lib/core/shop_rules.dart`.

## Web: formulário de cartão

Componente novo `frontend/components/site/CardFields.tsx` (`'use client'`), usado tanto no checkout
(`NewOrderForm.tsx`, dentro da seção 4 quando `payment_method==='cartao'`) quanto na página pública do link
de pagamento (`/pagar/[token]`):

- Carrega o script do Mercado Pago (`https://sdk.mercadopago.com/js/v2`) uma vez, inicializa
  `new MercadoPago(publicKey)`.
- Três campos seguros (`mp.fields.create('cardNumber'|'expirationDate'|'securityCode', { style:
  {...nossas cores/fonte...} }).mount(...)`), montados dentro de `div`s com a mesma aparência dos outros
  campos do formulário (borda, raio, cor de foco rosa) — só o conteúdo interno desses três é controlado
  pelo Mercado Pago.
- Campos normais, nossos: nome impresso no cartão, CPF (com máscara e validação via `isValidCpf`).
- Ao digitar o número, `onBinChange` dispara `mp.getPaymentMethods({ bin })` (detecta a bandeira) e, se o
  total for maior que R$150, `mp.getInstallments({ amount, bin })` (lista as opções sem juros) — populando
  um seletor de parcelas que só aparece nesse caso.
- No envio: `mp.fields.createCardToken({ cardholderName, identificationType:'CPF', identificationNumber })`
  → token; o formulário manda `{ card_token, payment_method_id, installments, cpf }` junto com o resto do
  pedido para `/api/cliente/pedidos` (ou, no link de pagamento, para a rota de cartão do link).
- Recusa: mostra o motivo (`card.reason`) e mantém a tela pronta para tentar de novo (gera um `cardForm`
  novo, já que o token antigo não serve mais).

## App Flutter: formulário de cartão

`mobile-client/lib/features/customer/card_fields.dart` (novo widget reaproveitado no checkout e, se algum
dia o link de pagamento ganhar uma tela própria no app — hoje ele só existe na web, então por enquanto só
o checkout do app precisa disso):

- Campos nativos (`AppTextField`) para número, validade, CVV, nome, CPF — com `autofillHints:
  [AutofillHints.creditCardNumber]`, `[AutofillHints.creditCardExpirationDate]`,
  `[AutofillHints.creditCardSecurityCode]`, `[AutofillHints.creditCardName]` respectivamente. Isso é o que
  permite ao Android sugerir um cartão salvo (inclusive um salvo via Google Pay/Password Manager) para
  autocompletar — sem abrir nada, só uma sugestão inline, como já acontece com senha.
  - Mobile precisa de `package:http` (já é dependência do mobile-client, visto em `new_order_screen.dart`) —
  chamadas diretas, sem token de acesso, só a chave pública:
  - `GET https://api.mercadopago.com/v1/payment_methods?bin=$bin&public_key=$key` (bandeira).
  - Se total > R$150: `GET https://api.mercadopago.com/v1/payment_methods/installments?bin=$bin&amount=$total&public_key=$key`
    (opções de parcelamento sem juros).
  - `POST https://api.mercadopago.com/v1/card_tokens?public_key=$key` com os dados do cartão → token.
- A chave pública vem de `GET /api/payment-config` (reaproveita o padrão já usado por `/api/pickup`:
  busca opcional, sem bloquear o carregamento da tela se falhar — nesse caso "Cartão" simplesmente não
  aparece como opção).

## Gestão (admin): visibilidade

- Onde hoje aparece "Pago (Pix)" nos pedidos (`admin_orders_screen.dart`, `OrdersManager.tsx`,
  `dashboard_screen.dart`), passa a mostrar "Pago (Cartão · 3x)" quando `payment_method==='cartao'` e
  `installments>1`, ou só "Pago (Cartão)" quando `installments===1`. É só um rótulo — reaproveita o mesmo
  campo `payment` que já existe.
- Notificação push de novo pagamento (`notifyPaid`) já é agnóstica quanto à forma de pagamento hoje (só
  fala valor/código) — não precisa mudar.

## Termos de Serviço e Política de Privacidade

`shared/legal.ts` muda em pontos pontuais, sem reescrever o documento:

- Termos §2 ("o que oferecemos"): "pagar com Pix" → "pagar com Pix ou cartão de crédito".
- Termos §5 ("preços e pagamento"): nova frase explicando que os dados do cartão vão direto ao Mercado
  Pago (nunca passam pelo nosso servidor), que pedimos CPF só para repassar ao Mercado Pago (não
  guardamos), e que parcelamento sem juros existe para pedidos acima de R$150, em até 12x.
- Termos §6 (devoluções): acrescenta que devolução de cartão volta pela operadora do cartão (pode levar até
  duas faturas), diferente do Pix (minutos).
- Privacidade §2 (dados que coletamos): novo item descrevendo exatamente o que é e não é recebido no
  pagamento por cartão (nenhum dado de cartão, CPF só repassado e não guardado, identificador do
  pagamento, bandeira, parcelas, status).
- Privacidade §4 (com quem compartilhamos): o item do Mercado Pago passa a mencionar cartão também, não só
  Pix.

## O que fica de fora por decisão explícita

- Juros no parcelamento (loja absorve a taxa do Mercado Pago, preço do produto nunca muda).
- Botão nativo "Pagar com Google Pay/Samsung Wallet" processando pelo Mercado Pago — não existe caminho
  hoje (ver seção acima). Só autofill nativo.
- Qualquer mudança no fluxo de Pix, no webhook ou nas devoluções — tudo isso já funciona e é só
  reaproveitado, não tocado.

## Teste

- `backend/tests/cpf.test.ts`: validação de CPF (válidos, inválidos, dígitos repetidos, tamanho errado).
- `backend/tests/card-pay.test.ts`: `cardFailReason` para os códigos mapeados e para um código
  desconhecido; `payWithCard` com um `mp()` substituído por mock de aprovado/recusado/pendente (mesmo
  padrão de mocking já usado em `backend/tests/mercadopago.test.ts`/`pix.test.ts`).
- `shared` tests: `parseShopOrder` com `payment_method='cartao'` (token ausente, CPF inválido, parcela
  acima do limite quando total ≤ R$150, parcela válida quando total > R$150).
- `mobile-common/test/shop_rules_test.dart`: mesmas validações do lado Dart.
- Depois do push, acompanhar o CI normalmente (mesmo processo das blocos anteriores).
