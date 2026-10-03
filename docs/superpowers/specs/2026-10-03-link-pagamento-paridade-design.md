# Pedido pago pelo link vira pedido do app (devolução e avisos iguais)

Data: 2026-10-03 · Estado: aguardando revisão · Abordagem escolhida pelo usuário: **A** (promover a pedido do app)

## Objetivo
Pedido registrado à mão pela confeiteira e pago pelo link de pagamento passa a se comportar **igual a um pedido feito no
app**: cancelar e devolver o Pix, "Devolver valor", o cliente pedir devolução (motivo, fotos, prazo), aprovar/negar,
estorno automático com nova tentativa, conciliação, faixas no card da gestão e os mesmos pushes para a confeiteira.
O cliente desses pedidos não tem conta: a **página do link** (`/pagar/<token>`) é a área dele, e o token substitui o login.

## Contexto verificado
- Devolução, cancelamento com estorno, faixas do card (`getOrders` → `shop_*`), travas de edição e pushes giram em torno de
  `shop_orders` (+ `customers`) e de `orders.shop_order_id`. `refunds.shop_order_id` e `refunds.customer_id` são NOT NULL.
- O link (feature anterior) vive em `order_pay_links` ligado a uma linha de `orders` sem `shop_order_id`.
- `customers.email` é único (lower), `password_hash`/`google_sub` podem ser nulos; login exige senha ou Google.

## Desenho
### 1. Promoção no pagamento
Quando um pagamento de link é aplicado (`markLinkPaid`, chamado pelo webhook, pela conciliação e pela conferência do
"Gerar novo Pix"), **na mesma transação**:
1. Cria um cliente convidado: `customers(email='link-<linkId>@convidado.invalid', name=orders.customer, phone='', guest=true)`
   (sem senha, sem Google: não consegue entrar).
2. Cria o `shop_orders` do pedido: `customer_id`=convidado, `owner_id`=dono do pedido, `contact_name`=orders.customer,
   `phone`='', `fulfillment`='retirada', `pickup_address`='', `scheduled_for`=coalesce(delivery_date, order_date),
   `time_window`='tarde' (valor técnico: a coluna é obrigatória; a página do link **não** mostra faixa de horário nem endereço de
   retirada para pedido promovido), `payment_method`='pix', `items`=`[{id:'manual-<orderId>', name:product, qty, unit_price}]`,
   `total`=**valor pago pelo link** (`order_pay_links.amount`; é o máximo devolvível), `public_code` novo
   (`allocateOrderCode`), `paid_at`=now(), `paid_mp_id`=pagamento aprovado, `mp_payment_ids`=os do link, `notes`=''.
3. Liga: `orders.shop_order_id`=novo id; `order_pay_links.shop_order_id`=novo id; `orders.payment`='pago'.
4. `syncDelivered` se o pedido já estava "entregue" (o prazo de 48h para pedir devolução conta da entrega, como no app).
5. Evento `pago` em `order_events` (ator `mercadopago`, `via: 'link'`).
6. Depois da transação: `notifyPaid` para a confeiteira (mesmo push do pedido do app). `notifyCustomerPaid` não tem
   aparelho para o convidado (vira no-op, como hoje para cliente sem app).

Casos de borda (iguais ao pedido do app):
- **Pedido cancelado** antes do pagamento chegar: promove já cancelado (`cancelled_at`, `cancelled_by='loja'`,
  motivo "Pedido cancelado pela loja") e chama `refundLatePayment` → devolve tudo sozinho (como `cancelLate` do app).
- **Pedido já marcado "pago" à mão** (dinheiro) e o Pix também chega: promove normalmente (o Pix entrou e é devolvível),
  registra aviso no log e manda push para a confeiteira "Pagamento em dobro no pedido #N — devolva pelo card". Não devolve
  sozinho (a loja decide).
- Links **já pagos antes desta mudança** (paid_at preenchido e sem `shop_order_id`): promovidos pela conciliação periódica
  (`reconcileLinks`) e ao abrir a página do link.

### 2. Gestão (app e site)
Sem tela nova: o pedido promovido já chega com `shop_*` em `GET /api/orders`, então o card mostra o número do pedido, as
faixas, "Cancelar e devolver o Pix", "Devolver valor", "Ver e decidir" e as travas do pedido do app (não exclui, não muda item
nem valor). O botão "Compartilhar link de pagamento" some (já pago).

### 3. Cliente: página do link
- `GET /api/pagar/:token`: quando promovido, devolve também `shop_order` no **mesmo formato** de `GET /api/cliente/pedidos`
  (reaproveita a montagem de `getCustomerOrders`, filtrando pelo pedido) — status, linha do tempo, devoluções, `refund.mode`.
- Rotas por token (repassam para as funções já existentes, com o `customer_id` do convidado):
  `POST /api/pagar/:token/cancelar` (= `cancelShopOrder`), `POST /api/pagar/:token/devolucao` (multipart, = `requestRefund`),
  `DELETE /api/pagar/:token/devolucao` (= `withdrawRefund`). Token inválido ou link não promovido → 404/409.
- Frontend: a página `/pagar/[token]` mostra, depois do pagamento, o mesmo cartão de pedido do cliente (componentes de
  `/cliente/pedidos`) e a página `/pagar/[token]/devolucao` usa o mesmo formulário (`RefundForm`). Os componentes
  `CancelOrder`, `RefundForm`, `WithdrawRefund` ganham o endereço da API por prop (padrão = o atual), como o `PixCopy`.
- Avisos ao cliente: sem push (não há app/conta). O que mudou aparece na própria página (igual ao site para quem tem conta).

### 4. Webhook e conciliação
- Ramo `link:` do webhook: estorno/chargeback de pagamento de link **promovido** → `reconcileRefunds(shop_order_id, payment)`
  (antes era ignorado). Aprovação → `markLinkPaid` (que agora promove).
- Conciliação de devoluções "processando" já cobre o pedido promovido (é um `shop_orders`).

### 5. Convidado não é cliente de verdade
`customers.guest BOOLEAN NOT NULL DEFAULT false`. Todo ponto que lista/conta clientes ou age sobre "minha conta" ignora
convidados: login por e-mail/Google e pedidos de privacidade/exportação. (Conferido: "clientes novos" do painel conta pelo
nome em `orders.customer`, não pela tabela `customers` — não muda.) Testes garantem cada um.

## Dados — migration `018_link_promotion.sql`
`ALTER TABLE customers ADD COLUMN guest BOOLEAN NOT NULL DEFAULT false;`
`ALTER TABLE order_pay_links ADD COLUMN shop_order_id INT REFERENCES shop_orders(id) ON DELETE RESTRICT;`
(sem conversão em SQL: a promoção dos links antigos é feita pelo código — ver 1.)

## Rulings do plano
- `items` do pedido promovido: uma única linha, qtd 1, nome `<qtd> × <produto>`, preço = valor pago (`orders.qty` pode ser fracionário e a devolução só aceita inteiros).
- Push de "pagamento em dobro" reaproveita `notifyPaid` (nome do cliente acrescido do aviso); não há tipo novo de push.
- Promoção de link antigo é silenciosa (sem push nem devolução automática).
- Varredura em `reconcileLinks` devolve pedido manual pago e cancelado pela loja que ficou sem devolução.
- Migration 018 também adiciona `shop_orders.manual`.

## Fora do escopo
E-mail/SMS ao cliente; cliente do link criar conta e "assumir" o pedido; pedidos não pagos (continuam manuais, como hoje).

## Testes
- Backend (Postgres real): promoção no webhook, na conciliação e no "Gerar novo Pix"; pedido cancelado → devolve sozinho;
  pago à mão + Pix → promove, avisa, não devolve; idempotência (reenvio do webhook não cria segundo `shop_orders`);
  cancelar/devolver/pedir/desistir pelas rotas de token (inclusive token de outro link → 404); estorno pelo webhook concilia;
  convidado fora de login, contagem de clientes e privacidade; card da gestão recebe `shop_*`.
- Frontend: tipos e build de teste. Flutter: card de pedido promovido mostra as ações de pedido do app e não o botão de link.

## Publicação
Migration 018 antes do build (`npm run migrate` → `infra/build.sh` → `sudo infra/install.sh`; app de gestão só se o card mudar).
