# Pedido pago pelo link vira pedido do app — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pedido registrado à mão e pago pelo link de pagamento passa a se comportar como pedido do app: devolução, cancelamento com estorno, pedido de devolução do cliente (pela página do link), avisos para a confeiteira e faixas no card da gestão.

**Architecture:** No momento em que o pagamento do link é aplicado (`markLinkPaid`), o pedido é *promovido*: cria-se um cliente convidado (sem login) e um `shop_orders` ligado ao `orders` existente. Daí em diante todo o código de devolução/cancelamento já existente (que gira em `shop_orders`) funciona sem mudança. A página `/pagar/[token]` vira a área do cliente: o token identifica o convidado e as rotas `/api/pagar/:token/*` repassam para as mesmas funções que o app do cliente usa.

**Tech Stack:** Next 15 (backend/frontend), Postgres, Vitest, Mercado Pago, React. Nenhuma mudança necessária nos apps Flutter (o card já reage a `shop_*`).

**Spec:** `docs/superpowers/specs/2026-10-03-link-pagamento-paridade-design.md` (+ rulings abaixo que a ajustam)

## Global Constraints

- Mensagens, comentários e testes em português do Brasil, no tom do código existente.
- Convidado: `customers.guest = true`, e-mail técnico `link-<linkId>@convidado.invalid`, **sem** `password_hash` nem `google_sub` (não consegue entrar).
- Promoção é idempotente (`order_pay_links.shop_order_id` já preenchido = não promove de novo) e acontece **na mesma transação** que grava `paid_at` do link.
- `shop_orders` promovido: `total` = valor pago pelo link (`order_pay_links.amount`); `payment_method='pix'`; `fulfillment='retirada'`; `phone=''`; `time_window='tarde'` (valor técnico exigido pela coluna); `manual=true`; `paid_at=now()`; `paid_mp_id` = pagamento aprovado; `mp_payment_ids` = os do link; `public_code` por `allocateOrderCode`.
- **Ruling (ajusta a spec):** `items` do `shop_orders` promovido = **uma linha** `[{ id: 'manual-<orderId>', name: '<qtd> × <produto>', qty: 1, unit_price: <valor pago> }]` (qtd formatada em pt-BR, ex.: `2 × Bolo de cenoura`; `0,5 × Brigadeiro`). Motivo: `orders.qty` pode ser fracionário (kg) e `requestRefund` só aceita quantidades inteiras ≥ 1 por item; uma linha de qtd 1 com o valor pago permite o cliente marcar "o pedido" sem quebrar a validação.
- **Ruling (ajusta a spec):** pagamento em dobro (pedido já "pago" à mão + Pix do link) avisa a confeiteira reaproveitando `notifyPaid` com o nome do cliente acrescido de ` (pagamento em dobro: devolva um deles pelo card)`; não cria tipo novo de push.
- Pedido cancelado antes do pagamento chegar: promove já cancelado (`cancelled_at=now()`, `cancelled_by='loja'`, `cancel_reason_code='outro'`, `cancel_reason='Pedido cancelado pela loja'`) e depois da transação chama `refundLatePayment(shopOrderId)`.
- Ciclo de imports proibido: `refunds.ts` **não** importa `pay-link.ts` (a conciliação de links passa a ser chamada pelo `jobs/tick`).
- **Produção:** nunca rodar `npm run migrate`, `infra/build.sh` ou `npm run build` sem `NEXT_DIST_DIR=.next-test`; todo comando de banco usa `TEST_DATABASE_URL=postgres://rayka_test@127.0.0.1:54329/rayka_test`; recriar o banco de teste (`bash ~/works/rayka-testdb-reset.sh`) antes de cada suíte completa; não tocar na porta 10005 nem em `backend/.env`. Publicar é do usuário.
- "Commit" = `bash ~/works/rayka-sgit.sh commit "<msg>"` (git sombra); não há git no projeto.

## Review Focus

1. **Promoção dupla / corrida:** webhook reenviado, conciliação e "Gerar novo Pix" chegando juntos não podem criar dois `shop_orders` nem dois convidados (Task 1).
2. **Pedido já "pago" à mão + Pix**, e **pedido cancelado + Pix**: promove, avisa, e o cancelado devolve sozinho — sem mudar `orders.payment` de pedido cancelado (Task 1).
3. **Isolamento do convidado:** não loga por e-mail/Google, não aparece em `GET /api/cliente/*` (precisa de sessão), e não afeta "clientes novos" (Task 4).
4. **Token de outro link / inválido / link não pago:** 404 ou 409 sem vazar nada; um token nunca age no pedido de outro (Task 3).
5. **Pagamento promovido volta pelo Mercado Pago:** estorno/chargeback de pagamento de link promovido concilia a devolução em vez de ser ignorado (Task 2).
6. **Páginas existentes não regridem:** `/cliente/pedidos` e o fluxo de devolução da conta continuam idênticos (Task 5).

---

### Task 0: Preparação (controlador)

- [ ] **Step 1:** `ss -ltn | grep 54329` — se o Postgres de teste não estiver de pé: `/usr/lib/postgresql/17/bin/pg_ctl -D ~/works/.rayka-pgtest -o "-p 54329 -k /tmp -c listen_addresses=127.0.0.1" -l ~/works/.rayka-pgtest/log start`.
- [ ] **Step 2:** baseline: `bash ~/works/rayka-testdb-reset.sh && cd backend && npx tsc --noEmit && TEST_DATABASE_URL=postgres://rayka_test@127.0.0.1:54329/rayka_test npm test` → 228/228; `cd ../frontend && npx tsc --noEmit`.
- [ ] **Step 3:** `bash ~/works/rayka-sgit.sh head` → anotar como BASE do trabalho.

---

### Task 1: Migration 018 e promoção do pedido no pagamento do link

**Files:**
- Create: `backend/migrations/018_link_promotion.sql`
- Modify: `backend/lib/pay-link.ts` (`markLinkPaid`; novas `promoteLink`, `afterPromotion`)
- Modify: `backend/lib/refunds.ts` (remove o import e a chamada de `reconcileLinks`: quebra o ciclo)
- Modify: `backend/app/api/jobs/tick/route.ts` (passa a chamar `reconcileLinks` junto da conciliação de 6 h)
- Modify: `backend/lib/shop-queries.ts` (`getCustomerOrders` devolve `manual`)
- Modify: `shared/types.ts` (`ShopOrder.manual: boolean`)
- Test: `backend/tests/pay-link.integration.test.ts`

**Interfaces:**
- Consumes: `tx`, `q`, `q1` de `./db`; `allocateOrderCode` de `./order-code`; `logEvent` de `./order-events`; `syncDelivered` de `./order-sync`; `notifyPaid` de `./push`; `refundLatePayment` de `./refunds`.
- Produces:
  - `markLinkPaid(linkId, mpPaymentId, paidAmount): Promise<number | null>` — mesmo retorno de hoje (id do pedido `orders`), agora **também promove**.
  - `promoteLink(linkId: number): Promise<boolean>` — promove um link já pago e ainda sem `shop_order_id` (links antigos); `true` se promoveu agora.
  - `type Promoted = { shopOrderId: number; ownerId: number; code: number; name: string; amount: number; late: boolean; double: boolean }`.

- [ ] **Step 1: Migration**

`backend/migrations/018_link_promotion.sql`:
```sql
-- Pedido pago pelo link de pagamento vira pedido do app (devolução e avisos iguais): cliente "convidado" + shop_orders ligado.
ALTER TABLE customers ADD COLUMN guest BOOLEAN NOT NULL DEFAULT false;          -- cliente criado pelo link: sem senha nem Google, não entra
ALTER TABLE shop_orders ADD COLUMN manual BOOLEAN NOT NULL DEFAULT false;       -- veio de um pedido registrado à mão pago pelo link
ALTER TABLE order_pay_links ADD COLUMN shop_order_id INT REFERENCES shop_orders(id) ON DELETE RESTRICT;
```
Aplicar **só no banco de teste**: `cd backend && DATABASE_URL=postgres://rayka_test@127.0.0.1:54329/rayka_test npm run migrate`.

- [ ] **Step 2: Quebrar o ciclo de imports (sem mudar comportamento)**

Em `backend/lib/refunds.ts`: apagar a linha `import { reconcileLinks } from './pay-link';` e a linha `n += await reconcileLinks();` dentro de `reconcilePending`. Em `backend/app/api/jobs/tick/route.ts`: `import { reconcileLinks } from '@/lib/pay-link';` e trocar a linha da conciliação de 6 h por:
```ts
  if (m < 5 && h % 6 === 0) { try { out.reconciled = (await reconcilePending()) + (await reconcileLinks()); } catch (e) { console.error('[jobs] reconcile', e); } } // a cada 6 h
```
Rodar `npx tsc --noEmit` e a suíte: o teste `reconcileLinks (F3)` chama `L.reconcileLinks()` direto e continua valendo.

- [ ] **Step 3: Escrever os testes que falham**

Em `backend/tests/pay-link.integration.test.ts`: (a) no `vi.mock('@/lib/mercadopago', ...)` acrescentar `createRefund: (...a: unknown[]) => createRefund(...a)` e declarar `const createRefund = vi.fn();` junto dos outros mocks (zerar no `beforeEach`; `createRefund.mockResolvedValue({ id: 55, status: 'approved', amount: 0 })` nos testes que precisarem); (b) `vi.mock('@/lib/push', ...)`: substituir pelos mocks que não fazem rede: `notifyPaid: (...a: unknown[]) => notifyPaid(...a)` (com `const notifyPaid = vi.fn();`), e `notifyCustomerPaid`, `notifyOwnerRefund`, `notifyCustomerRefund`, `notifyCancelled` como `vi.fn()` (mantendo `...(await orig<any>())` para o resto). Depois, dentro do `describe('markLinkPaid / handleLinkPayment')`, acrescentar:

```ts
    describe('promoção a pedido do app', () => {
      const shopOf = (order: number) => q1<any>('SELECT so.*, c.guest, c.email, c.password_hash, c.google_sub FROM orders o JOIN shop_orders so ON so.id = o.shop_order_id JOIN customers c ON c.id = so.customer_id WHERE o.id = $1', [order]);

      it('ao pagar: cria cliente convidado e o pedido do app, ligados, com o valor pago e uma linha de item', async () => {
        const { order, link, mp } = await withPix(50); // pedido de 2 × R$ 40, link de R$ 50 (sinal)
        expect(await L.markLinkPaid(link, mp, 50)).toBe(order);
        const so = await shopOf(order);
        expect(so).toMatchObject({ guest: true, manual: true, contact_name: 'Ana', total: 50, payment_method: 'pix', fulfillment: 'retirada', phone: '', paid_mp_id: mp, password_hash: null, google_sub: null });
        expect(so.email).toBe(`link-${link}@convidado.invalid`);
        expect(so.items).toEqual([{ id: `manual-${order}`, name: '2 × Bolo de cenoura', qty: 1, unit_price: 50 }]);
        expect(so.mp_payment_ids).toContain(mp);
        expect(so.public_code).toBeGreaterThanOrEqual(1000);
        expect((await q1<any>('SELECT shop_order_id FROM order_pay_links WHERE id = $1', [link]))!.shop_order_id).toBe(so.id);
        expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pago');
        expect((await q('SELECT type FROM order_events WHERE shop_order_id = $1', [so.id])).map((e: any) => e.type)).toContain('pago');
        expect(notifyPaid).toHaveBeenCalledWith(owner, expect.objectContaining({ id: so.id, code: so.public_code, customer: 'Ana', total: 50, items: 1 }));
      });

      it('quantidade fracionada vira "0,5 × produto"', async () => {
        fakePix();
        const s = await seedLink(null);
        await q('UPDATE orders SET qty = 0.5, unit_price = 30 WHERE id = $1', [s.order]);
        await q('UPDATE order_pay_links SET amount = 15 WHERE id = $1', [s.link]);
        await L.payLinkPix(s.token);
        const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [s.link]))!.mp_payment_id as string;
        await L.markLinkPaid(s.link, mp, 15);
        expect((await shopOf(s.order)).items[0].name).toBe('0,5 × Bolo de cenoura');
      });

      it('idempotente: repetir o webhook, a conciliação ou promoteLink não cria outro pedido nem outro convidado', async () => {
        const { order, link, mp } = await withPix(80);
        await L.markLinkPaid(link, mp, 80);
        const first = (await shopOf(order)).id;
        expect(await L.markLinkPaid(link, mp, 80)).toBeNull();
        expect(await L.promoteLink(link)).toBe(false);
        expect((await shopOf(order)).id).toBe(first);
        expect(Number((await q1<any>(`SELECT count(*) AS n FROM customers WHERE email = $1`, [`link-${link}@convidado.invalid`]))!.n)).toBe(1);
        expect(Number((await q1<any>('SELECT count(*) AS n FROM shop_orders so JOIN orders o ON o.shop_order_id = so.id WHERE o.id = $1', [order]))!.n)).toBe(1);
      });

      it('duas confirmações ao mesmo tempo (webhook + conciliação): um só pedido do app', async () => {
        const { order, link, mp } = await withPix(80);
        await Promise.all([L.markLinkPaid(link, mp, 80), L.markLinkPaid(link, mp, 80), L.promoteLink(link)]);
        expect(Number((await q1<any>('SELECT count(*) AS n FROM shop_orders so JOIN orders o ON o.shop_order_id = so.id WHERE o.id = $1', [order]))!.n)).toBe(1);
        expect(Number((await q1<any>(`SELECT count(*) AS n FROM customers WHERE email = $1`, [`link-${link}@convidado.invalid`]))!.n)).toBe(1);
      });

      it('pedido já "pago" à mão e o Pix também chega: promove, avisa "em dobro" e não devolve sozinho', async () => {
        const { order, link, mp } = await withPix(80);
        await q(`UPDATE orders SET payment = 'pago' WHERE id = $1`, [order]);
        vi.spyOn(console, 'warn').mockImplementation(() => {});
        await L.markLinkPaid(link, mp, 80);
        expect(await shopOf(order)).toMatchObject({ manual: true, cancelled_at: null });
        expect(notifyPaid).toHaveBeenCalledWith(owner, expect.objectContaining({ customer: expect.stringContaining('pagamento em dobro') }));
        expect(createRefund).not.toHaveBeenCalled();
      });

      it('pedido cancelado e o Pix chega: promove já cancelado, devolve tudo sozinho e o pedido segue cancelado (payment não vira "pago")', async () => {
        const { order, link, mp } = await withPix(80);
        await q(`UPDATE orders SET status = 'cancelado' WHERE id = $1`, [order]);
        vi.spyOn(console, 'warn').mockImplementation(() => {});
        createRefund.mockResolvedValue({ id: 77, status: 'approved', amount: 80 });
        await L.markLinkPaid(link, mp, 80);
        const so = await shopOf(order);
        expect(so.cancelled_at).toBeTruthy();
        expect(so.cancelled_by).toBe('loja');
        expect((await q1<any>('SELECT payment, status FROM orders WHERE id = $1', [order]))).toMatchObject({ status: 'cancelado', payment: 'pendente' });
        expect(createRefund).toHaveBeenCalledTimes(1);
        expect(createRefund.mock.calls[0][0]).toBe(mp);
        expect((await q1<any>(`SELECT kind, reason_code FROM refunds WHERE shop_order_id = $1`, [so.id]))).toMatchObject({ kind: 'sistema', reason_code: 'pago_apos_cancelamento' });
      });

      it('pedido entregue antes de pagar: o prazo de 48h para pedir devolução começa a contar (delivered_at preenchido)', async () => {
        const { order, link, mp } = await withPix(80);
        await q(`UPDATE orders SET status = 'entregue' WHERE id = $1`, [order]);
        await L.markLinkPaid(link, mp, 80);
        expect((await shopOf(order)).delivered_at).toBeTruthy();
      });

      it('promoteLink: link antigo já pago (sem pedido do app) é promovido; link não pago não', async () => {
        const { order, link, mp } = await withPix(80);
        await q(`UPDATE order_pay_links SET paid_at = now(), paid_mp_id = $2 WHERE id = $1`, [link, mp]);
        await q(`UPDATE orders SET payment = 'pago' WHERE id = $1`, [order]);
        expect(await L.promoteLink(link)).toBe(true);
        expect((await shopOf(order)).manual).toBe(true);
        const unpaid = await seedLink(null);
        expect(await L.promoteLink(unpaid.link)).toBe(false);
      });

      it('getCustomerOrders do convidado devolve o pedido no formato do cliente, com manual=true e pedido de devolução liberado após a entrega', async () => {
        const { order, link, mp } = await withPix(80);
        await L.markLinkPaid(link, mp, 80);
        const guest = (await shopOf(order)).customer_id;
        const { getCustomerOrders } = await import('@/lib/shop-queries');
        const [o] = await getCustomerOrders(guest);
        expect(o).toMatchObject({ manual: true, total: 80, paid: true, status: 'novo', can_cancel: true });
        expect(o.items).toHaveLength(1);
      });
    });
```
(A função `withPix` e `fakePix` já existem no arquivo, no `describe('markLinkPaid / handleLinkPayment')`; o novo `describe` fica **dentro** dele. `markLinkPaid` precisa de `L.promoteLink` exportada.)

- [ ] **Step 4: Rodar e ver falhar**

`bash ~/works/rayka-testdb-reset.sh && DATABASE_URL=… ` não precisa: o reset já migra até a 018. Rodar `cd backend && TEST_DATABASE_URL=postgres://rayka_test@127.0.0.1:54329/rayka_test npx vitest run tests/pay-link.integration.test.ts -t "promoção"` → FALHA (`L.promoteLink is not a function` / sem `shop_order_id`).

- [ ] **Step 5: Implementar a promoção em `backend/lib/pay-link.ts`**

Importações a acrescentar no topo: 
```ts
import type { PoolClient } from 'pg';
import { allocateOrderCode } from './order-code';
import { logEvent } from './order-events';
import { syncDelivered } from './order-sync';
import { notifyPaid } from './push';
import { refundLatePayment } from './refunds';
```
Acrescentar (antes de `markLinkPaid`):
```ts
export type Promoted = { shopOrderId: number; ownerId: number; code: number; name: string; amount: number; late: boolean; double: boolean; delivered: boolean };

const qtyLabel = (n: number) => String(n).replace('.', ',');

/** Dentro da transação: o pedido registrado à mão pago pelo link vira pedido do app (cliente convidado + shop_orders ligado).
 *  Idempotente: link já promovido não faz nada. `paidMpId` = o pagamento aprovado. */
async function promoteInTx(c: PoolClient, linkId: number, paidMpId: string): Promise<Promoted | null> {
  const l = (await c.query(
    `SELECT l.amount::float8 AS amount, l.mp_payment_ids, l.shop_order_id, o.id AS order_id, o.user_id, o.customer, o.product, o.qty::float8 AS qty,
            o.status, o.payment, o.order_date, o.delivery_date
       FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.id = $1 FOR UPDATE OF l`, [linkId])).rows[0];
  if (!l || l.shop_order_id != null) return null;
  const cancelled = l.status === 'cancelado';
  const guest = (await c.query(`INSERT INTO customers(email, name, guest) VALUES ($1, $2, true) RETURNING id`, [`link-${linkId}@convidado.invalid`, l.customer])).rows[0].id as number;
  const code = await allocateOrderCode(async (n) => (await c.query('SELECT 1 FROM shop_orders WHERE public_code = $1', [n])).rowCount! > 0);
  const items = [{ id: `manual-${l.order_id}`, name: `${qtyLabel(l.qty)} × ${l.product}`, qty: 1, unit_price: l.amount }];
  const so = (await c.query(
    `INSERT INTO shop_orders(customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for, time_window, payment_method, items, total, public_code,
                             paid_at, paid_mp_id, mp_payment_ids, manual, cancelled_at, cancelled_by, cancel_reason_code, cancel_reason)
     VALUES ($1,$2,$3,'','retirada',COALESCE($4::date,$5::date),'tarde','pix',$6,$7,$8, now(),$9,$10,true,$11,$12,$13,$14) RETURNING id`,
    [guest, l.user_id, l.customer, l.delivery_date, l.order_date, JSON.stringify(items), l.amount, code, paidMpId, l.mp_payment_ids,
      cancelled ? new Date() : null, cancelled ? 'loja' : null, cancelled ? 'outro' : null, cancelled ? 'Pedido cancelado pela loja' : null])).rows[0].id as number;
  await c.query(`UPDATE orders SET shop_order_id = $1, payment = CASE WHEN status = 'cancelado' THEN payment ELSE 'pago' END WHERE id = $2`, [so, l.order_id]);
  await c.query(`UPDATE order_pay_links SET shop_order_id = $1 WHERE id = $2`, [so, linkId]);
  await logEvent(so, 'mercadopago', 'pago', { payment: paidMpId, amount: l.amount, via: 'link' }, c);
  return { shopOrderId: so, ownerId: l.user_id, code, name: l.customer, amount: l.amount, late: cancelled, double: !cancelled && l.payment === 'pago', delivered: l.status === 'entregue' };
}

/** Depois de confirmada a transação da promoção: prazo de devolução, aviso à confeiteira e, se o pedido já estava cancelado, devolve o Pix sozinho. */
async function afterPromotion(p: Promoted): Promise<void> {
  if (p.delivered) await syncDelivered(p.shopOrderId);
  void notifyPaid(p.ownerId, { id: p.shopOrderId, code: p.code, customer: p.double ? `${p.name} (pagamento em dobro: devolva um deles pelo card)` : p.name, total: p.amount, items: 1 });
  if (p.late) await refundLatePayment(p.shopOrderId);
}

/** Promove um link já pago que ainda não virou pedido do app (links pagos antes desta funcionalidade). `true` se promoveu agora. */
export async function promoteLink(linkId: number): Promise<boolean> {
  const p = await tx(async (c) => {
    const l = (await c.query(`SELECT paid_mp_id, shop_order_id FROM order_pay_links WHERE id = $1 AND paid_at IS NOT NULL FOR UPDATE`, [linkId])).rows[0];
    return l && l.shop_order_id == null && l.paid_mp_id ? promoteInTx(c, linkId, l.paid_mp_id) : null;
  });
  if (p) await afterPromotion(p);
  return !!p;
}
```
Trocar `markLinkPaid` por (o texto original do `before`/`warn`/`UPDATE` é preservado; só muda o fim e o retorno):
```ts
export async function markLinkPaid(linkId: number, mpPaymentId: string, paidAmount: number): Promise<number | null> {
  const done = await tx(async (c) => {
    const before = (await c.query(
      `SELECT o.id, o.status, o.payment FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.id = $1 FOR UPDATE OF o`, [linkId])).rows[0] as
      { id: number; status: string; payment: string } | undefined;
    const { rows } = await c.query(
      `UPDATE order_pay_links SET paid_at = now(), paid_mp_id = $2
        WHERE id = $1 AND $2 = ANY(mp_payment_ids) AND paid_at IS NULL AND $3::numeric >= amount RETURNING order_id`,
      [linkId, mpPaymentId, paidAmount]);
    if (!rows[0]) return null;
    if (before && (before.status === 'cancelado' || before.payment === 'pago')) {
      console.warn('[pagamento por link] pagamento recebido para pedido cancelado ou já pago — devolver pelo painel do Mercado Pago, se for o caso',
        { linkId, orderId: before.id, status: before.status, payment: before.payment, mpPaymentId, paidAmount });
    }
    const promo = await promoteInTx(c, linkId, mpPaymentId);
    return { orderId: rows[0].order_id as number, promo };
  });
  if (!done) return null;
  if (done.promo) await afterPromotion(done.promo);
  return done.orderId;
}
```
(`promoteInTx` já faz o `UPDATE orders SET payment = 'pago'` condicionado a não cancelado; remover o `UPDATE orders ... AND status <> 'cancelado'` antigo.) Em `reconcileLinks`, depois do laço de links abertos, acrescentar a promoção dos antigos:
```ts
  const legacy = await q<{ id: number }>(`SELECT id FROM order_pay_links WHERE paid_at IS NOT NULL AND shop_order_id IS NULL`);
  for (const l of legacy) { try { if (await promoteLink(l.id)) n++; } catch (e) { console.error('[pagamento por link] falha ao promover link antigo', l.id, e); } }
```
Em `backend/lib/shop-queries.ts`, no SELECT de `getCustomerOrders`, acrescentar `so.manual` ao final da lista de colunas de `so.` (o `...r` do retorno já o repassa). Em `shared/types.ts`, no tipo `ShopOrder`, acrescentar `manual: boolean; // pedido registrado à mão pago pelo link (sem horário nem endereço)`.

- [ ] **Step 6: Rodar e ver passar**

`bash ~/works/rayka-testdb-reset.sh && cd backend && npx tsc --noEmit && TEST_DATABASE_URL=postgres://rayka_test@127.0.0.1:54329/rayka_test npm test` → tudo verde (inclusive os testes antigos de `markLinkPaid`; se algum teste antigo esperar `console.warn` ou saída diferente, ajuste só o que a promoção muda e registre no relatório). `cd ../frontend && npx tsc --noEmit`.

- [ ] **Step 7: Checkpoint** — `bash ~/works/rayka-sgit.sh commit "feat: pedido pago pelo link vira pedido do app (promoção)"`.

---

### Task 2: Webhook — estorno de pagamento de link promovido concilia a devolução

**Files:**
- Modify: `backend/lib/pay-link.ts` (`handleLinkPayment`)
- Test: `backend/tests/pay-link.integration.test.ts`

**Interfaces:**
- Consumes (Task 1): `order_pay_links.shop_order_id`, `promoteLink`; `reconcileRefunds(orderId, paymentId)` de `./refunds` (já importado no Task 1 junto de `refundLatePayment`).
- Produces: `handleLinkPayment(pay, linkId)` — estorno/chargeback de link **promovido** chama `reconcileRefunds(shop_order_id, String(pay.id))`; estorno de link não promovido continua ignorado.

- [ ] **Step 1: Teste que falha** (no `describe('markLinkPaid / handleLinkPayment')`):
```ts
    it('estorno/chargeback de pagamento de link promovido: concilia a devolução (antes era ignorado)', async () => {
      const { order, link, mp } = await withPix(80);
      await L.markLinkPaid(link, mp, 80);
      const so = (await q1<any>('SELECT shop_order_id FROM order_pay_links WHERE id = $1', [link]))!.shop_order_id as number;
      getRefunds.mockResolvedValue([{ id: 31, amount: 20, status: 'approved' }]);
      await L.handleLinkPayment({ id: Number(mp), status: 'refunded', transaction_amount: 80, external_reference: `link:${link}`, transaction_amount_refunded: 20 } as any, link);
      expect(getRefunds).toHaveBeenCalledWith(mp);
      expect((await q1<any>('SELECT refunded_total FROM shop_orders WHERE id = $1', [so]))!.refunded_total).toBe(20);
      void order;
    });

    it('estorno de link que ainda não foi promovido (nunca pago) continua ignorado', async () => {
      const { link, mp } = await withPix(80);
      getRefunds.mockClear();
      expect(await L.handleLinkPayment({ id: Number(mp), status: 'refunded', transaction_amount: 80, external_reference: `link:${link}` } as any, link)).toMatchObject({ ok: true, status: 'refunded' });
      expect(getRefunds).not.toHaveBeenCalled();
    });
```
Acrescentar ao harness: `const getRefunds = vi.fn();` + `getRefunds: (...a: unknown[]) => getRefunds(...a)` no mock do mercadopago + `getRefunds.mockReset()` no `beforeEach`. (Se `reconcileRefunds` exigir mais campos do mock de `getRefunds` — veja `backend/lib/refunds.ts:334` — complete o mock com o formato real `MpRefund` e registre no relatório.)

- [ ] **Step 2:** rodar e ver falhar (`getRefunds` não chamado).
- [ ] **Step 3: Implementar** — em `handleLinkPayment`, antes do `if (... pay.status !== 'approved')`:
```ts
  if (Number.isInteger(linkId) && (['refunded', 'charged_back'].includes(pay.status) || (pay as any).transaction_amount_refunded > 0)) {
    const l = await q1<{ shop_order_id: number | null; paid_mp_id: string | null }>('SELECT shop_order_id, paid_mp_id FROM order_pay_links WHERE id = $1', [linkId]);
    if (l?.shop_order_id && l.paid_mp_id === String(pay.id)) await reconcileRefunds(l.shop_order_id, String(pay.id));
    return { ok: true, status: pay.status };
  }
```
(importar `reconcileRefunds` de `./refunds`.)
- [ ] **Step 4:** suíte inteira (banco recriado) + `tsc`. **Step 5:** checkpoint `commit "feat: estorno de pagamento de link promovido concilia a devolução"`.

---

### Task 3: Rotas do cliente por token (ver, cancelar, pedir/desistir de devolução)

**Files:**
- Create: `backend/lib/refund-form.ts`
- Modify: `backend/app/api/cliente/pedidos/[id]/devolucao/route.ts` (usa o helper; comportamento idêntico)
- Modify: `backend/lib/pay-link.ts` (`linkGuest`; `PayLinkView.shop_order`; `payLinkView` preenche)
- Create: `backend/app/api/pagar/[token]/cancelar/route.ts`, `backend/app/api/pagar/[token]/devolucao/route.ts`
- Modify: `shared/types.ts` (`PayLinkView.shop_order`)
- Test: `backend/tests/pay-link.integration.test.ts`

**Interfaces:**
- Consumes: `isPayToken`, `promoteLink` (Task 1); `getCustomerOrders` (`./shop-queries`); `cancelShopOrder` (`./cancel`); `requestRefund`, `withdrawRefund` (`./refunds`).
- Produces:
  - `linkGuest(token: string): Promise<{ customerId: number; shopOrderId: number } | null>` — resolve o convidado do link **pago** (promove na hora se for link antigo); `null` se token inválido, inexistente ou link não pago.
  - `readRefundForm(req: Request): Promise<{ ok: true; input: RequestInput } | { ok: false; status: number; error: string }>`.
  - `PayLinkView.shop_order: ShopOrder | null` (pedido no formato do cliente, quando o link está pago).
  - `POST /api/pagar/:token/cancelar` (`{reason_code, reason}`) → mesmo corpo de resposta de `/api/cliente/pedidos/:id/cancelar`; `POST/DELETE /api/pagar/:token/devolucao` (multipart / vazio) → mesmo de `/api/cliente/pedidos/:id/devolucao`.

- [ ] **Step 1: Testes que falham** (novo `describe('rotas do cliente por token')` dentro do `d(...)`, antes do marcador):
```ts
  describe('rotas do cliente por token', () => {
    const ctx = (token: string) => ({ params: Promise.resolve({ token }) }) as any;
    const json = (body: unknown) => new Request('http://x/api', { method: 'POST', body: JSON.stringify(body) });
    async function paidLink(amount = 80) {
      fakePix();
      const s = await seedLink(amount);
      await L.payLinkPix(s.token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [s.link]))!.mp_payment_id as string;
      await L.markLinkPaid(s.link, mp, amount);
      return { ...s, mp };
    }

    it('payLinkView de link pago traz shop_order no formato do cliente; antes de pagar, null', async () => {
      const open = await seedLink(80);
      expect((await L.payLinkView(open.token))!.shop_order).toBeNull();
      const p = await paidLink();
      const v = await L.payLinkView(p.token);
      expect(v!.state).toBe('pago');
      expect(v!.shop_order).toMatchObject({ manual: true, total: 80, paid: true, can_cancel: true });
    });

    it('linkGuest: só para link pago; promove link antigo na hora; token ruim/de link não pago → null', async () => {
      const open = await seedLink(80);
      expect(await L.linkGuest(open.token)).toBeNull();
      expect(await L.linkGuest('curto')).toBeNull();
      expect(await L.linkGuest('A'.repeat(43))).toBeNull();
      const p = await paidLink();
      expect(await L.linkGuest(p.token)).toMatchObject({ customerId: expect.any(Number), shopOrderId: expect.any(Number) });
      // link antigo: pago, sem pedido do app
      fakePix();
      const old = await seedLink(80);
      await L.payLinkPix(old.token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [old.link]))!.mp_payment_id as string;
      await q(`UPDATE order_pay_links SET paid_at = now(), paid_mp_id = $2 WHERE id = $1`, [old.link, mp]);
      await q(`UPDATE orders SET payment = 'pago' WHERE id = $1`, [old.order]);
      expect(await L.linkGuest(old.token)).toMatchObject({ shopOrderId: expect.any(Number) });
    });

    it('cancelar: pedido pago e não iniciado cancela e devolve o Pix; token de outro link não age neste pedido', async () => {
      const a = await paidLink(), b = await paidLink();
      createRefund.mockResolvedValue({ id: 90, status: 'approved', amount: 80 });
      const C = await import('@/app/api/pagar/[token]/cancelar/route');
      const r = await C.POST(json({ reason_code: 'desisti', reason: '' }), ctx(a.token));
      expect(r.status).toBe(200);
      expect(await r.json()).toMatchObject({ ok: true, state: 'cancelled', refunded: 80 });
      expect((await q1<any>('SELECT status FROM orders WHERE id = $1', [a.order]))!.status).toBe('cancelado');
      expect((await q1<any>('SELECT status FROM orders WHERE id = $1', [b.order]))!.status).not.toBe('cancelado'); // o outro link não foi tocado
      expect((await C.POST(json({}), ctx('A'.repeat(43)))).status).toBe(404);
      expect((await C.POST(json({}), ctx('curto'))).status).toBe(404);
      const unpaid = await seedLink(80);
      expect((await C.POST(json({}), ctx(unpaid.token))).status).toBe(404);
    });

    it('pedir devolução (pedido entregue, com foto) e desistir, pelo token', async () => {
      const p = await paidLink();
      await q(`UPDATE orders SET status = 'entregue' WHERE id = $1`, [p.order]);
      const { syncDelivered } = await import('@/lib/order-sync');
      await syncDelivered((await L.linkGuest(p.token))!.shopOrderId);
      const D = await import('@/app/api/pagar/[token]/devolucao/route');
      const fd = new FormData();
      fd.set('reason_code', 'avariado'); fd.set('description', 'Chegou todo amassado e derretido.');
      fd.set('items', JSON.stringify([{ id: `manual-${p.order}`, qty: 1 }]));
      fd.append('fotos', new File([Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(40)])], 'a.png', { type: 'image/png' }));
      const made = await D.POST(new Request('http://x/api', { method: 'POST', body: fd }), ctx(p.token));
      expect(made.status).toBe(201);
      const so = (await L.linkGuest(p.token))!.shopOrderId;
      expect((await q1<any>(`SELECT status, requested_amount FROM refunds WHERE shop_order_id = $1`, [so]))).toMatchObject({ status: 'solicitado', requested_amount: 80 });
      expect((await L.payLinkView(p.token))!.shop_order!.refunds[0]).toMatchObject({ status: 'solicitado' });
      const gone = await D.DELETE(new Request('http://x/api', { method: 'DELETE' }), ctx(p.token));
      expect(gone.status).toBe(200);
      expect((await q1<any>(`SELECT status FROM refunds WHERE shop_order_id = $1`, [so]))!.status).toBe('desistiu');
      expect((await D.POST(new Request('http://x/api', { method: 'POST', body: new FormData() }), ctx('A'.repeat(43)))).status).toBe(404);
    });

    it('a confeiteira aprova a devolução do pedido promovido e o estorno sai pelo pagamento do link', async () => {
      const p = await paidLink();
      await q(`UPDATE orders SET status = 'entregue' WHERE id = $1`, [p.order]);
      const so = (await L.linkGuest(p.token))!.shopOrderId;
      const { syncDelivered } = await import('@/lib/order-sync');
      await syncDelivered(so);
      const D = await import('@/app/api/pagar/[token]/devolucao/route');
      const fd = new FormData();
      fd.set('reason_code', 'avariado'); fd.set('description', 'Chegou todo amassado e derretido.'); fd.set('items', JSON.stringify([{ id: `manual-${p.order}`, qty: 1 }]));
      fd.append('fotos', new File([Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(40)])], 'a.png', { type: 'image/png' }));
      await D.POST(new Request('http://x/api', { method: 'POST', body: fd }), ctx(p.token));
      const rid = (await q1<any>('SELECT id FROM refunds WHERE shop_order_id = $1', [so]))!.id as number;
      createRefund.mockResolvedValue({ id: 91, status: 'approved', amount: 80 });
      const { decideRefund } = await import('@/lib/refunds');
      expect(await decideRefund(rid, owner, { approve: true })).toMatchObject({ ok: true, status: 'aprovado' });
      expect(createRefund.mock.calls.at(-1)![0]).toBe(p.mp);
    });
  });
```
Os mocks de `createRefund` e de push já foram acrescentados no Task 1.

- [ ] **Step 2: Rodar e ver falhar** (`L.linkGuest is not a function`; rotas inexistentes).

- [ ] **Step 3: `backend/lib/refund-form.ts`** (extrai o parsing de `cliente/pedidos/[id]/devolucao`):
```ts
import { sniffImage, stripJpegMetadata } from '@/lib/image';
import type { RequestInput } from '@/lib/refunds';
import { MAX_REFUND_PHOTOS, MAX_REFUND_PHOTO_BYTES } from '@shared/refund';

/** Lê o multipart do pedido de devolução (reason_code, description, items em JSON e até 5 fotos "fotos"): valida e limpa as fotos. */
export async function readRefundForm(req: Request): Promise<{ ok: true; input: RequestInput } | { ok: false; status: number; error: string }> {
  let form: FormData;
  try { form = await req.formData(); } catch { return { ok: false, status: 400, error: 'Envie o pedido de devolução pelo aplicativo ou pelo site.' }; }
  const files = form.getAll('fotos').filter((f): f is File => typeof f !== 'string' && f.size > 0);
  if (files.length > MAX_REFUND_PHOTOS) return { ok: false, status: 400, error: `Envie no máximo ${MAX_REFUND_PHOTOS} fotos.` };
  const photos: { type: string; data: Buffer }[] = [];
  for (const f of files) {
    if (f.size > MAX_REFUND_PHOTO_BYTES) return { ok: false, status: 413, error: 'Uma das fotos é grande demais (máximo de 2 MB).' };
    const data = Buffer.from(await f.arrayBuffer());
    const type = sniffImage(data);
    if (!type) return { ok: false, status: 415, error: 'Use fotos JPG, PNG ou WebP.' };
    photos.push({ type, data: type === 'image/jpeg' ? stripJpegMetadata(data) : data }); // sem EXIF/localização
  }
  let items: unknown = [];
  try { items = JSON.parse(String(form.get('items') ?? '[]')); } catch { return { ok: false, status: 400, error: 'Confira os itens marcados.' }; }
  return { ok: true, input: { reason_code: form.get('reason_code'), description: form.get('description'), items, photos } };
}
```
Reescrever `backend/app/api/cliente/pedidos/[id]/devolucao/route.ts` para usar o helper (o `DELETE` não muda):
```ts
import { fail, ok } from '@/lib/api';
import { customerRoute } from '@/lib/customer-api';
import { readRefundForm } from '@/lib/refund-form';
import { requestRefund, withdrawRefund } from '@/lib/refunds';

// O cliente pede devolução de um pedido pago (depois que a produção começou). multipart/form-data:
// reason_code, description, items (JSON [{id, qty}]) e fotos (de 0 a 5 arquivos "fotos").
export const POST = customerRoute(async (req, customer, { id }) => {
  const orderId = Number(id);
  if (!Number.isInteger(orderId)) return fail('Pedido não encontrado.', 404);
  const f = await readRefundForm(req);
  if (!f.ok) return fail(f.error, f.status);
  const r = await requestRefund(orderId, customer.id, f.input);
  return r.ok ? ok({ ok: true, id: r.refundId }, 201) : fail(r.error, r.status);
});

// Desistir da solicitação, enquanto ela ainda não foi analisada.
export const DELETE = customerRoute(async (_req, customer, { id }) => {
  const r = await withdrawRefund(Number(id), customer.id);
  return r.ok ? ok() : fail(r.error, r.status);
});
```
Rodar a suíte inteira nesse ponto: os testes do cliente (`refunds.integration`, etc.) continuam verdes.

- [ ] **Step 4: `linkGuest` e `payLinkView.shop_order` em `backend/lib/pay-link.ts`**
Importar `getCustomerOrders` de `./shop-queries` e `type ShopOrder` de `@shared/types`. `PayLinkView` ganha `shop_order: ShopOrder | null;`. Acrescentar:
```ts
/** Convidado dono do pedido de um link PAGO (promove na hora um link antigo). null: token ruim, inexistente ou ainda não pago. */
export async function linkGuest(token: string): Promise<{ customerId: number; shopOrderId: number } | null> {
  if (!isPayToken(token)) return null;
  const read = () => q1<{ id: number; paid_at: Date | null; shop_order_id: number | null; customer_id: number | null }>(
    `SELECT l.id, l.paid_at, l.shop_order_id, so.customer_id FROM order_pay_links l LEFT JOIN shop_orders so ON so.id = l.shop_order_id WHERE l.token = $1`, [token]);
  let l = await read();
  if (!l?.paid_at) return null;
  if (!l.shop_order_id) { await promoteLink(l.id); l = await read(); }
  return l?.shop_order_id && l.customer_id ? { customerId: l.customer_id, shopOrderId: l.shop_order_id } : null;
}
```
No fim de `payLinkView`, antes do `return`: 
```ts
  const g = r.paid_at ? await linkGuest(token) : null;
  const shop_order = g ? ((await getCustomerOrders(g.customerId)).find((o) => o.id === g.shopOrderId) ?? null) : null;
```
e incluir `shop_order` no objeto devolvido. Em `shared/types.ts` acrescentar o mesmo campo em `PayLinkView`.

- [ ] **Step 5: Rotas por token**
`backend/app/api/pagar/[token]/cancelar/route.ts`:
```ts
import { fail, ok } from '@/lib/api';
import { cancelShopOrder } from '@/lib/cancel';
import { linkGuest } from '@/lib/pay-link';
import { CANCEL_REASONS } from '@shared/refund';
import { cleanText } from '@shared/text';

// O cliente do link cancela o pedido pago enquanto a produção não começou: o Pix volta na hora (mesma regra do app).
export async function POST(req: Request, ctx: { params: Promise<{ token: string }> }) {
  const { token } = await ctx.params;
  try {
    const g = await linkGuest(token);
    if (!g) return fail('Link não encontrado.', 404);
    const b = await req.json().catch(() => ({}));
    const code = CANCEL_REASONS.some((r) => r.id === b?.reason_code) ? String(b.reason_code) : 'outro';
    const note = cleanText(b?.reason).slice(0, 150);
    if (code === 'outro' && b?.reason_code && note.length < 3) return fail('Conte o motivo do cancelamento.');
    const r = await cancelShopOrder(g.shopOrderId, g.customerId, note, code);
    return r.ok ? ok(r) : fail(r.error, r.status);
  } catch (e) {
    console.error(e);
    return fail('Algo deu errado. Tente de novo em instantes.', 500);
  }
}
```
`backend/app/api/pagar/[token]/devolucao/route.ts`:
```ts
import { fail, ok } from '@/lib/api';
import { linkGuest } from '@/lib/pay-link';
import { readRefundForm } from '@/lib/refund-form';
import { requestRefund, withdrawRefund } from '@/lib/refunds';

type Ctx = { params: Promise<{ token: string }> };
const oops = (e: unknown) => { console.error(e); return fail('Algo deu errado. Tente de novo em instantes.', 500); };

// Pedir devolução pelo link (multipart: reason_code, description, items, fotos). Mesmas regras do app.
export async function POST(req: Request, ctx: Ctx) {
  const { token } = await ctx.params;
  try {
    const g = await linkGuest(token);
    if (!g) return fail('Link não encontrado.', 404);
    const f = await readRefundForm(req);
    if (!f.ok) return fail(f.error, f.status);
    const r = await requestRefund(g.shopOrderId, g.customerId, f.input);
    return r.ok ? ok({ ok: true, id: r.refundId }, 201) : fail(r.error, r.status);
  } catch (e) { return oops(e); }
}

// Desistir da solicitação, enquanto ela ainda não foi analisada.
export async function DELETE(_req: Request, ctx: Ctx) {
  const { token } = await ctx.params;
  try {
    const g = await linkGuest(token);
    if (!g) return fail('Link não encontrado.', 404);
    const r = await withdrawRefund(g.shopOrderId, g.customerId);
    return r.ok ? ok() : fail(r.error, r.status);
  } catch (e) { return oops(e); }
}
```
- [ ] **Step 6:** `bash ~/works/rayka-testdb-reset.sh && cd backend && npx tsc --noEmit && TEST_DATABASE_URL=… npm test` → verde; `cd ../frontend && npx tsc --noEmit` (o tipo `PayLinkView` mudou: `PayLink.tsx` ainda compila porque só lê campos existentes); `cd ../backend && NEXT_DIST_DIR=.next-test npm run build && rm -rf .next-test` lista `/api/pagar/[token]/cancelar` e `/api/pagar/[token]/devolucao`.
- [ ] **Step 7:** checkpoint `commit "feat: cliente do link vê o pedido e cancela/pede devolução pelo token"`.

---

### Task 4: Convidado não é cliente de verdade

**Files:**
- Modify: `backend/lib/customer-auth.ts` (convidado fora do login e do Google)
- Test: `backend/tests/pay-link.integration.test.ts`

**Interfaces:** Consumes Task 1 (`customers.guest`). Produces: `loginCustomer`/`upsertGoogleCustomer` nunca entram numa conta `guest`.

- [ ] **Step 1: Testes que falham** (descrever 'isolamento do convidado'):
```ts
  describe('isolamento do convidado', () => {
    it('não entra por e-mail/senha nem pelo Google, e "clientes novos" do painel não muda', async () => {
      fakePix();
      const s = await seedLink(80);
      await L.payLinkPix(s.token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [s.link]))!.mp_payment_id as string;
      await L.markLinkPaid(s.link, mp, 80);
      const email = `link-${s.link}@convidado.invalid`;
      const A = await import('@/lib/customer-auth');
      expect(await A.loginCustomer(email, 'qualquer')).toEqual({ error: 'invalid' });
      await expect(A.upsertGoogleCustomer({ sub: `sub-${Date.now()}`, email, name: 'Intruso' })).rejects.toThrow(); // não captura o convidado (e o e-mail já está em uso)
      expect((await q1<any>('SELECT google_sub, guest FROM customers WHERE lower(email) = $1', [email]))).toMatchObject({ google_sub: null, guest: true });
    });
  });
```
(O convidado tem e-mail `.invalid` e nenhuma senha/Google: o login falha como "invalid" e o Google não pode vincular nem criar conta com o mesmo e-mail.)
- [ ] **Step 2: Rodar e ver falhar** (o `UPDATE` vincula o Google ao convidado).
- [ ] **Step 3: Implementar** — em `loginCustomer`, trocar o SELECT por `... FROM customers WHERE lower(email) = lower($1) AND NOT guest`; em `upsertGoogleCustomer`, no `UPDATE ... WHERE lower(email) = lower($1)` acrescentar `AND NOT guest`; o `SELECT ... WHERE google_sub = $1` não muda (convidado nunca tem `google_sub`).
- [ ] **Step 4:** suíte inteira + tsc. **Step 5:** checkpoint `commit "fix: convidado do link não entra por login nem Google"`.

---

### Task 5: Página do link vira a área do cliente (frontend)

**Files:**
- Create: `frontend/components/OrderCard.tsx` (extraído de `app/cliente/pedidos/page.tsx`)
- Modify: `frontend/app/cliente/pedidos/page.tsx` (usa `OrderCard`; comportamento idêntico)
- Modify: `frontend/components/CancelOrder.tsx`, `frontend/components/WithdrawRefund.tsx`, `frontend/components/RefundBox.tsx` (endereços por prop, com o padrão atual)
- Modify: `frontend/app/cliente/pedidos/[id]/devolucao/RefundForm.tsx` e `page.tsx` (prop `api`)
- Create: `frontend/app/pagar/[token]/devolucao/page.tsx`
- Modify: `frontend/app/pagar/[token]/PayLink.tsx` (mostra o cartão do pedido quando há `shop_order`)

**Interfaces:**
- Consumes: `PayLinkView.shop_order: ShopOrder | null`; rotas `/api/pagar/:token/cancelar|devolucao`.
- Produces: `type OrderLinks = { cancelUrl: string; refundApi: string; refundHref: string; backHref: string }` e `orderLinks(o: ShopOrder, token?: string): OrderLinks` em `frontend/components/OrderCard.tsx` (sem token = conta do cliente; com token = link).

Endereços: conta → `cancelUrl=/api/cliente/pedidos/<id>/cancelar`, `refundApi=/api/cliente/pedidos/<id>/devolucao`, `refundHref=/cliente/pedidos/<id>/devolucao`, `backHref=/cliente/pedidos`. Link → `cancelUrl=/api/pagar/<token>/cancelar`, `refundApi=/api/pagar/<token>/devolucao`, `refundHref=/pagar/<token>/devolucao`, `backHref=/pagar/<token>`.

- [ ] **Step 1:** ler `frontend/app/cliente/pedidos/page.tsx`, `components/{CancelOrder,WithdrawRefund,RefundBox,OrderTimeline}.tsx`, `app/cliente/pedidos/[id]/devolucao/{page,RefundForm}.tsx`.
- [ ] **Step 2: `OrderCard`**: mover para `frontend/components/OrderCard.tsx` o `PayBadge` e o conteúdo do `<Card className="p-4">…</Card>` que hoje está dentro do `orders.map` de `page.tsx`, como `export function OrderCard({ o, links }: { o: ShopOrder; links: OrderLinks })` (componente de servidor, sem `'use client'`), exportando também `orderLinks`. Mudanças em relação ao original, e somente estas: (a) `CancelOrder`, `RefundBox` e `WithdrawRefund` recebem `links`; (b) quando `o.manual` é `true`, **não** renderizar a linha de data/janela nem a de endereço (`CalendarDays`/`MapPin`); mostrar só a data `dateBR(o.scheduled_for)` como "Para <data>" (ícone `CalendarDays`); (c) com `manual`, o texto "Pagamento: Pix" continua. `page.tsx` passa a fazer `orders.map((o) => <li key={o.id}><OrderCard o={o} links={orderLinks(o)} /></li>)` mantendo o restante da página.
- [ ] **Step 3: Props de endereço (padrão = comportamento atual):**
  - `CancelOrder({ orderId, code, paid, total, cancelUrl })`: usar `cancelUrl ?? \`/api/cliente/pedidos/${orderId}/cancelar\`` na chamada `api(...)`.
  - `WithdrawRefund({ orderId, api: refundApi })`: `refundApi ?? \`/api/cliente/pedidos/${orderId}/devolucao\``.
  - `RefundBox`: o `Link` "Pedir devolução" usa `links?.refundHref ?? \`/cliente/pedidos/${o.id}/devolucao\``; repassa `links?.refundApi` ao `WithdrawRefund`.
  - `RefundForm({ order, api, backHref })`: `fetch(api ?? \`/api/cliente/pedidos/${order.id}/devolucao\`, …)` e `router.replace(backHref ?? '/cliente/pedidos')`; o link "Voltar" da página usa `backHref`.
  Os chamadores atuais não mudam de comportamento (conferir com `grep -rn "CancelOrder\|RefundBox\|WithdrawRefund\|RefundForm" frontend`).
- [ ] **Step 4: `/pagar/[token]/devolucao/page.tsx`** (servidor):
```tsx
import Link from 'next/link';
import { notFound, redirect } from 'next/navigation';
import { apiPublic } from '@/lib/server-api';
import type { PayLinkView } from '@shared/types';
import { RefundForm } from '@/app/cliente/pedidos/[id]/devolucao/RefundForm';

export const metadata = { title: 'Pedir devolução' };

export default async function Page({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  const view = await apiPublic<PayLinkView>(`/api/pagar/${encodeURIComponent(token)}`, 0);
  if (!view) notFound();
  const o = view.shop_order;
  if (!o || o.refund.mode !== 'solicitacao') redirect(`/pagar/${token}`);
  return (
    <>
      <Link href={`/pagar/${token}`} className="inline-flex min-h-11 items-center text-sm font-semibold text-pink-strong">← Voltar ao pedido</Link>
      <h1 className="font-display text-3xl font-semibold leading-tight">Pedir devolução do pedido #{o.code}</h1>
      <p className="mt-1 text-muted">Conte o que aconteceu. A confeiteira analisa e responde aqui mesmo, nesta página.</p>
      <RefundForm order={o} api={`/api/pagar/${encodeURIComponent(token)}/devolucao`} backHref={`/pagar/${token}`} />
    </>
  );
}
```
- [ ] **Step 5: `PayLink.tsx`**: quando `view.shop_order` existir, trocar o bloco "Pagamento confirmado"/"cancelado" por: o aviso de pagamento confirmado (mantém o `role="status"` verde) seguido de `<OrderCard o={view.shop_order} links={orderLinks(view.shop_order, token)} />`. Como `OrderCard` é componente de servidor e `PayLink` é cliente, **renderizar o `OrderCard` no `page.tsx` (servidor) e passá-lo a `PayLink` como `children`**: em `page.tsx`, `<PayLink token={token} view={view}>{view.shop_order && <OrderCard o={view.shop_order} links={orderLinks(view.shop_order, token)} />}</PayLink>`, e `PayLink` renderiza `{children}` abaixo do aviso de pagamento. O polling continua só enquanto `waiting` (Pix na tela); depois de pago a página usa `router.refresh()` quando o estado mudar, e o cliente vê o cartão atualizado a cada ação (as ações já chamam `router.refresh()`).
- [ ] **Step 6: Verificar (sem tocar na produção):** `cd frontend && npx tsc --noEmit && NEXT_DIST_DIR=.next-test npm run build && rm -rf .next-test` — build lista `/pagar/[token]` e `/pagar/[token]/devolucao`; conferir `grep -rn "OrderCard" frontend/app/cliente/pedidos/page.tsx` e que `/cliente/pedidos` não perdeu nenhuma seção (comparar o JSX movido com o original: cabeçalho, timeline, `CancelInfo`, itens, total, data/endereço/pagamento, cancelar, aviso de produção, `RefundBox`, `PixCopy`).
- [ ] **Step 7:** checkpoint `commit "feat: página do link vira a área do cliente (pedido, cancelar e pedir devolução)"`.

---

### Task 6: Documentação e verificação final

**Files:** Modify: `docs/API.md`, `docs/superpowers/specs/2026-10-03-link-pagamento-paridade-design.md` (rulings dos itens e do push em dobro).

- [ ] **Step 1: `docs/API.md`** — na seção "Link de pagamento": documentar a promoção (cliente convidado, `shop_orders.manual`, 1 linha de item, total = valor pago), `GET /api/pagar/:token` agora com `shop_order`, e as rotas `POST /api/pagar/:token/cancelar`, `POST|DELETE /api/pagar/:token/devolucao` (404 para token inválido ou link não pago); o estorno de pagamento de link promovido é conciliado; links antigos são promovidos pela conciliação e ao abrir a página.
- [ ] **Step 2: Spec** — acrescentar a nota "Rulings do plano: 1 linha de item com qtd 1 e nome `<qtd> × <produto>`; push em dobro reaproveita `notifyPaid`".
- [ ] **Step 3: Verificação completa:** `bash ~/works/rayka-testdb-reset.sh`; `cd backend && npx tsc --noEmit && TEST_DATABASE_URL=… npm test`; `cd ../frontend && npx tsc --noEmit`; `cd ../mobile-server && export PATH="$HOME/development/flutter/bin:$PATH" && flutter test`; `cd ../mobile-client && flutter test` (o campo novo `manual` é ignorado pelos apps).
- [ ] **Step 4: Produção intacta:** `ss -ltnp | grep :10005` e `ls -la --time-style=full-iso backend/.next/BUILD_ID frontend/.next/BUILD_ID` — nada deve ter mudado em relação ao início do trabalho (o build das 15:26/15:27 de hoje).
- [ ] **Step 5:** checkpoint `commit "docs: paridade do pedido pago pelo link"`.

---

## Self-Review (feito contra a spec)

- **Cobertura:** promoção na transação + convidado + `shop_orders` + ligações + evento + push + cancelado/dobro/entregue/legados (Task 1); estorno por webhook (Task 2); `shop_order` na página + cancelar + pedir/desistir devolução por token (Task 3); isolamento do convidado (Task 4); frontend com cartão reaproveitado e página de devolução (Task 5); docs e verificação (Task 6). "Clientes novos" do painel não precisa mudar (conta por `orders.customer`; conferido na spec).
- **Placeholders:** nenhum; todo passo de código tem o código; os passos de frontend descrevem a mudança exata sobre código que o implementador lê no Step 1.
- **Consistência de tipos:** `Promoted`, `markLinkPaid`, `promoteLink`, `linkGuest`, `readRefundForm`, `PayLinkView.shop_order`, `ShopOrder.manual`, `OrderLinks`/`orderLinks` com as mesmas assinaturas entre as tasks.
- **Review Focus:** 1 → testes de idempotência/corrida (Task 1); 2 → dobro/cancelado (Task 1); 3 → Task 4; 4 → Task 3; 5 → Task 2; 6 → Task 5, Step 6.
