# Link de pagamento para pedidos da confeiteira — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A confeiteira compartilha, pela aba Pedidos do app de gestão, um link público de pagamento Pix para pedidos que ela mesma registrou e ainda não foram pagos; o pagamento confirmado pelo Mercado Pago marca o pedido como "pago".

**Architecture:** Mecanismo paralelo ao Pix da loja: tabela `order_pay_links` ligada a `orders` (não a `shop_orders`), rotas públicas por token (`/api/pagar/:token`), tratador próprio no webhook (`external_reference = link:<id>`) e página pública `/pagar/[token]` que reaproveita o `PixCopy`. O app de gestão chama `POST /api/orders/:id/pagamento` e abre o compartilhamento do sistema.

**Tech Stack:** Next 15 (backend `backend/`, frontend `frontend/`), Postgres, Vitest, Mercado Pago `/v1/payments`, Flutter (`mobile-common`, `mobile-server`), `share_plus` (já em `mobile-common`).

**Spec:** `docs/superpowers/specs/2026-10-03-link-pagamento-design.md`

## Global Constraints

- Idioma de toda mensagem ao usuário, comentário e teste: português do Brasil, no tom do código existente.
- Valor cobrado: `0,01 ≤ amount ≤ total da linha (qty × unit_price)`, arredondado a 2 casas; padrão = total.
- Elegível: pedido da conta logada, `shop_order_id IS NULL`, `status ≠ 'cancelado'`, `payment ∈ ('pendente','sinal')`.
- Token: 32 bytes aleatórios em base64url (43 caracteres, `[A-Za-z0-9_-]`); token fora desse formato = 404 sem consultar o banco.
- Máximo de Pix por link: `MAX_PIX_ATTEMPTS` (10) de `backend/lib/pix.ts`; validade: `pixExpiresMinutes()`.
- `external_reference` do link: `link:<order_pay_links.id>`; chave de idempotência: `rayka-link-<id>-<tentativa>`. Pedido da loja continua `external_reference = <id>` e `rayka-order-<id>-<tentativa>` (não pode mudar).
- E-mail do pagador: `PAY_LINK_PAYER_EMAIL` ou `pagamentos@<domínio de SITE_URL sem "www.">`.
- Webhook de link: só marca pago se o pagamento está em `mp_payment_ids` do link e `transaction_amount ≥ amount`; estorno/chargeback de link é ignorado; nada de devolução automática.
- **Produção:** nunca rodar `npm run migrate`, `infra/build.sh` ou `npm run build` sem `NEXT_DIST_DIR=.next-test` apontando para o banco/serviço de produção. Todo comando de banco neste plano usa `DATABASE_URL="$TEST_DATABASE_URL"` (banco de TESTE separado). Não matar nem reiniciar o processo da porta 10005. Publicar (migrate, build, install, publish-apps) é do usuário.
- O projeto **não está em git**: onde o fluxo padrão pediria commit, o plano pede um *checkpoint* (rodar os testes da tarefa e conferir que passam).

## Review Focus

1. **Prévia de link (WhatsApp/robôs) abre a URL:** `GET /api/pagar/:token` nunca pode gerar Pix nem alterar nada. (Task 3, teste "GET não gera Pix".)
2. **Confeiteira baixa o valor depois que o cliente já tem um Pix:** o Pix antigo é cancelado; se o cliente pagar o antigo no último instante com valor menor que o novo, o webhook não marca pago e registra aviso no log. (Task 3, testes de troca de valor e de `markLinkPaid`.)
3. **Pedido marcado "pago" à mão (dinheiro) e o cliente abre o link depois:** a página mostra "pago" e o `POST …/pix` não cria Pix. (Task 3.)
4. **Pedido cancelado ou excluído depois de compartilhar:** link devolve "cancelado" (409 ao gerar Pix) ou 404 se o pedido sumiu (cascata). (Tasks 2 e 3.)
5. **Duplo toque em "Pagar com Pix" e reenvio do webhook:** mesma chave de idempotência por tentativa; segunda entrega do webhook não repete efeito. (Task 4.)
6. **Valor digitado com vírgula, ponto de milhar ou 3 casas** (`30,5`, `1.000,00`, `10,005`): lido em pt-BR e arredondado a 2 casas; acima do total ou abaixo de 0,01 é recusado. (Tasks 2 e 6.)

---

### Task 0: Preparação (backup e banco de teste)

**Files:** nenhum arquivo do projeto.

- [ ] **Step 1: Backup (o projeto não tem git)**

Run:
```bash
cd /home/g_snts/works && tar czf rayka-estoque-backup-$(date +%Y%m%d-%H%M)-antes-link-pagamento.tar.gz \
  --exclude=node_modules --exclude=.next --exclude=.next-test --exclude=build --exclude=.dart_tool rayka-estoque
ls -lh rayka-estoque-backup-*-antes-link-pagamento.tar.gz
```
Expected: arquivo listado (alguns MB).

- [ ] **Step 2: Conferir o banco de TESTE (nunca o de produção)**

Run:
```bash
echo "${TEST_DATABASE_URL:-<vazio>}" | sed -E 's#//[^@]*@#//***@#'
```
Expected: uma URL de banco de teste. Se vier `<vazio>`, criar um banco separado no Postgres local e exportar a variável antes de seguir:
```bash
createdb rayka_test   # ou: sudo -u postgres createdb -O $USER rayka_test
export TEST_DATABASE_URL="postgres://$USER@127.0.0.1:5432/rayka_test"
cd /home/g_snts/works/rayka-estoque/backend && DATABASE_URL="$TEST_DATABASE_URL" npm run migrate
```
Expected: migrations `001`…`016` aplicadas no banco de teste. **Conferir que a URL NÃO é a do `backend/.env`.**

- [ ] **Step 3: Linha de base verde**

Run: `cd /home/g_snts/works/rayka-estoque/backend && npx tsc --noEmit && TEST_DATABASE_URL="$TEST_DATABASE_URL" npm test`
Expected: tsc sem erros; todos os testes passam (referência anterior: 193). Se algo falhar já aqui, parar e avisar o usuário.

---

### Task 1: Migration 017 e referência própria no `createPixPayment`

**Files:**
- Create: `backend/migrations/017_order_pay_links.sql`
- Modify: `backend/lib/mercadopago.ts` (função `createPixPayment`)
- Test: `backend/tests/mercadopago-reference.test.ts`

**Interfaces:**
- Produces: tabela `order_pay_links` (colunas abaixo); `createPixPayment(o: { orderId: number; code: number; total: number; email: string; name: string; attempt?: number; expiresAt: Date; reference?: string })` — com `reference` ausente, comportamento idêntico ao atual.

- [ ] **Step 1: Escrever o teste que falha**

`backend/tests/mercadopago-reference.test.ts`:
```ts
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createPixPayment } from '@/lib/mercadopago';

afterEach(() => vi.unstubAllGlobals());

const mpOk = () => new Response(JSON.stringify({ id: 9, status: 'pending', point_of_interaction: { transaction_data: { qr_code: 'CODE', qr_code_base64: 'QR' } } }), { status: 201 });
const base = { code: 4821, total: 10, email: 'a@b.com', name: 'Ana Souza', expiresAt: new Date(Date.now() + 3600_000) };

describe('createPixPayment: referência do pagamento', () => {
  it('pedido da loja mantém external_reference e a chave de idempotência de sempre', async () => {
    const f = vi.fn(async () => mpOk());
    vi.stubGlobal('fetch', f);
    await createPixPayment({ ...base, orderId: 12, attempt: 2 });
    const [, init] = f.mock.calls[0] as any;
    expect(JSON.parse(init.body).external_reference).toBe('12');
    expect(init.headers['X-Idempotency-Key']).toBe('rayka-order-12-2');
  });

  it('link de pagamento usa a própria referência e a própria chave', async () => {
    const f = vi.fn(async () => mpOk());
    vi.stubGlobal('fetch', f);
    await createPixPayment({ ...base, orderId: 5, reference: 'link:5' });
    const [, init] = f.mock.calls[0] as any;
    expect(JSON.parse(init.body).external_reference).toBe('link:5');
    expect(init.headers['X-Idempotency-Key']).toBe('rayka-link-5-1');
  });
});
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && npx vitest run tests/mercadopago-reference.test.ts`
Expected: o segundo teste FALHA (`external_reference` vem `'5'`).

- [ ] **Step 3: Implementar**

Em `backend/lib/mercadopago.ts`, trocar a assinatura e os dois pontos de uso de `orderId`:
```ts
/** Cria a cobrança Pix do pedido. `external_reference` é o id do pedido da loja (é por ele que o webhook acha o pedido);
 *  `reference` (ex.: "link:12") troca isso para outras cobranças, como o link de pagamento de pedido registrado à mão. */
export async function createPixPayment(o: { orderId: number; code: number; total: number; email: string; name: string; attempt?: number; expiresAt: Date; reference?: string }) {
  const idem = o.reference ? o.reference.replace(':', '-') : `order-${o.orderId}`;
  const p = await mp<MpPayment>('/v1/payments', {
    method: 'POST',
    headers: { 'X-Idempotency-Key': `rayka-${idem}-${o.attempt ?? 1}` }, // um Pix novo (tentativa seguinte) precisa de outra chave
    body: JSON.stringify({
      transaction_amount: o.total,
      description: `Pedido #${o.code} - Raykookie`,
      payment_method_id: 'pix',
      payer: { email: o.email, first_name: o.name.split(' ')[0] },
      external_reference: o.reference ?? String(o.orderId),
      date_of_expiration: mpDate(o.expiresAt),
      notification_url: siteUrl('/api/webhooks/mercadopago'),
    }),
  });
```
(o restante da função — `const td = ...` em diante — não muda.)

- [ ] **Step 4: Migration**

`backend/migrations/017_order_pay_links.sql`:
```sql
-- Link de pagamento Pix para pedido registrado à mão (sem conta de cliente). Um link por pedido; o token vai na URL pública.
CREATE TABLE order_pay_links (
  id             SERIAL PRIMARY KEY,
  order_id       INT NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
  token          TEXT NOT NULL UNIQUE,
  amount         NUMERIC(12,2) NOT NULL CHECK (amount > 0),   -- o que será cobrado (pode ser menos que o total, se já houve sinal)
  mp_payment_id  TEXT,                                          -- Pix mais recente
  mp_payment_ids TEXT[] NOT NULL DEFAULT '{}',                  -- todos os Pix gerados (o webhook aceita qualquer um)
  pix_qr_code    TEXT NOT NULL DEFAULT '',
  pix_qr_base64  TEXT NOT NULL DEFAULT '',
  pix_expires_at TIMESTAMPTZ,
  pix_attempts   INT NOT NULL DEFAULT 0,
  paid_at        TIMESTAMPTZ,
  paid_mp_id     TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 5: Rodar os testes e aplicar a migration NO BANCO DE TESTE**

Run:
```bash
cd backend && npx vitest run tests/mercadopago-reference.test.ts tests/mercadopago.test.ts tests/pix.test.ts
DATABASE_URL="$TEST_DATABASE_URL" npm run migrate
```
Expected: testes passam; migrate mostra `017_order_pay_links.sql` aplicada (as anteriores "já aplicada").

- [ ] **Step 6: Checkpoint** — `npx tsc --noEmit` sem erros.

---

### Task 2: Núcleo do link — criar/atualizar e consultar (`ensurePayLink`, `payLinkView`)

**Files:**
- Create: `backend/lib/pay-link.ts`
- Test: `backend/tests/pay-link.integration.test.ts`

**Interfaces:**
- Consumes: `q`, `q1`, `tx` de `./db`; `cancelPayment`, `mpConfigured` de `./mercadopago`; `siteUrl` de `./site`.
- Produces (usados pelas Tasks 3–4):
  - `type EnsureResult = { ok: true; url: string; amount: number } | { ok: false; status: number; error: string }`
  - `ensurePayLink(userId: number, orderId: number, amount: number | null): Promise<EnsureResult>`
  - `type PayLinkView = { state: 'aberto' | 'pago' | 'cancelado'; amount: number; order: { number: number; customer: string; product: string; qty: number; unit_price: number; delivery_date: string | null }; pix: { code: string; qr_base64: string; expires_in: number; expired: boolean } | null }`
  - `payLinkView(token: string): Promise<PayLinkView | null>`
  - `isPayToken(token: string): boolean`, `LINK_REF = 'link:'`, `payerEmail(): string`

- [ ] **Step 1: Escrever o arquivo de teste com a base e os testes desta tarefa**

`backend/tests/pay-link.integration.test.ts`:
```ts
// Link de pagamento de pedido registrado à mão, contra um Postgres de verdade (precisa de TEST_DATABASE_URL; sem ela, é ignorado).
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

const TEST_DB = process.env.TEST_DATABASE_URL;
const d = TEST_DB ? describe : describe.skip;
if (TEST_DB) process.env.DATABASE_URL = TEST_DB;

let owner = 0;
const mpOn = vi.fn(() => true);
const createPixPayment = vi.fn(), getPayment = vi.fn(), cancelPayment = vi.fn();
vi.mock('@/lib/auth', () => ({ getUser: async () => ({ id: owner, username: 'rayka' }) }));
vi.mock('@/lib/mercadopago', async (orig) => ({
  ...(await orig<any>()),
  mpConfigured: () => mpOn(),
  validSignature: () => true,
  createPixPayment: (...a: unknown[]) => createPixPayment(...a),
  getPayment: (...a: unknown[]) => getPayment(...a),
  cancelPayment: (...a: unknown[]) => cancelPayment(...a),
}));
vi.mock('@/lib/push', async (orig) => ({ ...(await orig<any>()), notifyPaid: vi.fn(), notifyCustomerPaid: vi.fn() }));

d('link de pagamento (banco real)', () => {
  let q: typeof import('@/lib/db').q, q1: typeof import('@/lib/db').q1, pool: typeof import('@/lib/db').pool;
  let L: typeof import('@/lib/pay-link');
  let n = 0, other = 0, customer = 0;

  /** pedido registrado à mão: 2 × R$ 40 = R$ 80 (ou pedido do app, se `shop`) */
  async function seed(o: { status?: string; payment?: string; shop?: boolean; user?: number } = {}) {
    n++;
    let shopId: number | null = null;
    if (o.shop) {
      shopId = (await q1<{ id: number }>(
        `INSERT INTO shop_orders(customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for, time_window, payment_method, items, total, public_code)
         VALUES ($1,$2,'Maria','11999999999','retirada','2026-10-20','tarde','pix','[{"id":"1","name":"Cookie","qty":1,"unit_price":6}]',6,$3) RETURNING id`,
        [customer, owner, 30000 + n]))!.id;
    }
    return (await q1<{ id: number }>(
      `INSERT INTO orders(user_id, number, customer, product, qty, unit_price, status, payment, shop_order_id) VALUES ($1,$2,'Ana','Bolo de cenoura',2,40,$3,$4,$5) RETURNING id`,
      [o.user ?? owner, n, o.status ?? 'novo', o.payment ?? 'pendente', shopId]))!.id;
  }

  beforeAll(async () => {
    const db = await import('@/lib/db'); q = db.q; q1 = db.q1; pool = db.pool;
    L = await import('@/lib/pay-link');
    const stamp = Date.now();
    owner = (await q1<any>(`INSERT INTO users(username, password_hash) VALUES ($1, 'x') RETURNING id`, [`link-${stamp}`]))!.id;
    other = (await q1<any>(`INSERT INTO users(username, password_hash) VALUES ($1, 'x') RETURNING id`, [`link-outra-${stamp}`]))!.id;
    customer = (await q1<any>(`INSERT INTO customers(email, name) VALUES ($1, 'Maria') RETURNING id`, [`link${stamp}@x.com`]))!.id;
  });
  afterAll(async () => { await pool?.end(); });
  beforeEach(() => {
    mpOn.mockReset(); mpOn.mockReturnValue(true);
    createPixPayment.mockReset(); getPayment.mockReset(); cancelPayment.mockReset();
  });

  describe('ensurePayLink', () => {
    it('cria o link com o total do pedido por padrão e devolve a URL pública', async () => {
      const id = await seed();
      const r = await L.ensurePayLink(owner, id, null);
      expect(r).toMatchObject({ ok: true, amount: 80 });
      expect((r as any).url).toMatch(/\/pagar\/[A-Za-z0-9_-]{43}$/);
    });

    it('reabrir "Compartilhar" devolve a mesma URL', async () => {
      const id = await seed();
      const a: any = await L.ensurePayLink(owner, id, null);
      const b: any = await L.ensurePayLink(owner, id, null);
      expect(b.url).toBe(a.url);
      expect((await q('SELECT 1 FROM order_pay_links WHERE order_id = $1', [id])).length).toBe(1);
    });

    it('valor editável: aceita de 0,01 até o total (com arredondamento) e recusa fora disso', async () => {
      const id = await seed({ payment: 'sinal' });
      expect(await L.ensurePayLink(owner, id, 30.5)).toMatchObject({ ok: true, amount: 30.5 });
      expect(await L.ensurePayLink(owner, id, 10.005)).toMatchObject({ ok: true }); // arredonda a 2 casas
      expect(await L.ensurePayLink(owner, id, 80)).toMatchObject({ ok: true, amount: 80 });
      expect(await L.ensurePayLink(owner, id, 80.01)).toMatchObject({ ok: false, status: 400 });
      expect(await L.ensurePayLink(owner, id, 0)).toMatchObject({ ok: false, status: 400 });
      expect(await L.ensurePayLink(owner, id, 0.001)).toMatchObject({ ok: false, status: 400 });
    });

    it('recusa pedido de outra conta, inexistente, do app, já pago, cancelado e Mercado Pago desligado', async () => {
      expect(await L.ensurePayLink(other, await seed(), null)).toMatchObject({ ok: false, status: 404 });
      expect(await L.ensurePayLink(owner, 999999999, null)).toMatchObject({ ok: false, status: 404 });
      expect(await L.ensurePayLink(owner, await seed({ shop: true }), null)).toMatchObject({ ok: false, status: 400 });
      expect(await L.ensurePayLink(owner, await seed({ payment: 'pago' }), null)).toMatchObject({ ok: false, status: 409 });
      expect(await L.ensurePayLink(owner, await seed({ status: 'cancelado' }), null)).toMatchObject({ ok: false, status: 409 });
      mpOn.mockReturnValue(false);
      expect(await L.ensurePayLink(owner, await seed(), null)).toMatchObject({ ok: false, status: 503 });
    });
  });

  describe('payLinkView', () => {
    it('token inexistente ou fora do formato: null', async () => {
      expect(await L.payLinkView('A'.repeat(43))).toBeNull();
      expect(await L.payLinkView('curto')).toBeNull();
      expect(await L.payLinkView("x' OR '1'='1")).toBeNull();
    });

    it('link novo: aberto, com o resumo do pedido e sem Pix', async () => {
      const id = await seed();
      const url = ((await L.ensurePayLink(owner, id, 50)) as any).url as string;
      const v = await L.payLinkView(url.split('/').pop()!);
      expect(v).toMatchObject({ state: 'aberto', amount: 50, pix: null, order: { customer: 'Ana', product: 'Bolo de cenoura', qty: 2, unit_price: 40 } });
    });

    it('pedido marcado "pago" à mão ou cancelado depois de compartilhar muda o estado da página', async () => {
      const id = await seed();
      const token = (((await L.ensurePayLink(owner, id, null)) as any).url as string).split('/').pop()!;
      await q(`UPDATE orders SET payment = 'pago' WHERE id = $1`, [id]);
      expect((await L.payLinkView(token))!.state).toBe('pago');
      await q(`UPDATE orders SET payment = 'pendente', status = 'cancelado' WHERE id = $1`, [id]);
      expect((await L.payLinkView(token))!.state).toBe('cancelado');
    });

    it('pedido excluído leva o link junto (404 na página)', async () => {
      const id = await seed();
      const token = (((await L.ensurePayLink(owner, id, null)) as any).url as string).split('/').pop()!;
      await q('DELETE FROM orders WHERE id = $1', [id]);
      expect(await L.payLinkView(token)).toBeNull();
    });
  });

  // (testes das próximas tarefas entram aqui)
});
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && TEST_DATABASE_URL="$TEST_DATABASE_URL" npx vitest run tests/pay-link.integration.test.ts`
Expected: FALHA — `Cannot find module '@/lib/pay-link'`.

- [ ] **Step 3: Implementar `backend/lib/pay-link.ts`**

```ts
import { randomBytes } from 'node:crypto';
import { q1 } from './db';
import { cancelPayment, mpConfigured } from './mercadopago';
import { siteUrl } from './site';

/** Prefixo do `external_reference` dos Pix de link de pagamento (o do pedido da loja é só o número do pedido). */
export const LINK_REF = 'link:';

/** E-mail do pagador no Mercado Pago (pedido registrado à mão não tem conta de cliente). */
export const payerEmail = () =>
  process.env.PAY_LINK_PAYER_EMAIL || `pagamentos@${new URL(siteUrl()).hostname.replace(/^www\./, '')}`;

/** 32 bytes em base64url = 43 caracteres. Qualquer outra coisa nem chega ao banco. */
export const isPayToken = (t: string) => /^[A-Za-z0-9_-]{43}$/.test(t);
const newToken = () => randomBytes(32).toString('base64url');

export type EnsureResult = { ok: true; url: string; amount: number } | { ok: false; status: number; error: string };

/** Cria (ou reaproveita) o link de pagamento de um pedido registrado à mão. `amount` nulo cobra o total da linha. */
export async function ensurePayLink(userId: number, orderId: number, amount: number | null): Promise<EnsureResult> {
  const o = await q1<{ status: string; payment: string; shop_order_id: number | null; total: number }>(
    `SELECT status, payment, shop_order_id, (qty * unit_price)::float8 AS total FROM orders WHERE id = $1 AND user_id = $2`, [orderId, userId]);
  if (!o) return { ok: false, status: 404, error: 'Pedido não encontrado.' };
  if (o.shop_order_id != null) return { ok: false, status: 400, error: 'Pedido do app: o cliente paga pelo próprio app.' };
  if (o.status === 'cancelado') return { ok: false, status: 409, error: 'Este pedido foi cancelado.' };
  if (o.payment === 'pago') return { ok: false, status: 409, error: 'Este pedido já está pago.' };
  const total = Math.round(o.total * 100) / 100;
  const value = Math.round((amount ?? total) * 100) / 100;
  if (!Number.isFinite(value) || value < 0.01 || value > total) {
    return { ok: false, status: 400, error: `O valor precisa ficar entre R$ 0,01 e R$ ${total.toFixed(2).replace('.', ',')}.` };
  }
  if (!mpConfigured()) return { ok: false, status: 503, error: 'O Pix não está disponível agora. Tente de novo mais tarde.' };

  const prev = await q1<{ amount: number; mp_payment_id: string | null; paid_at: Date | null }>(
    `SELECT amount::float8 AS amount, mp_payment_id, paid_at FROM order_pay_links WHERE order_id = $1`, [orderId]);
  if (prev?.paid_at) return { ok: false, status: 409, error: 'Este pedido já foi pago pelo link.' };
  // valor mudou: o Pix que existia cobra o valor antigo, então some junto (a página gera outro ao pagar)
  const row = await q1<{ token: string }>(
    `INSERT INTO order_pay_links(order_id, token, amount) VALUES ($1, $2, $3)
     ON CONFLICT (order_id) DO UPDATE SET
       amount = EXCLUDED.amount,
       pix_qr_code    = CASE WHEN order_pay_links.amount = EXCLUDED.amount THEN order_pay_links.pix_qr_code ELSE '' END,
       pix_qr_base64  = CASE WHEN order_pay_links.amount = EXCLUDED.amount THEN order_pay_links.pix_qr_base64 ELSE '' END,
       pix_expires_at = CASE WHEN order_pay_links.amount = EXCLUDED.amount THEN order_pay_links.pix_expires_at ELSE NULL END
     WHERE order_pay_links.paid_at IS NULL
     RETURNING token`,
    [orderId, newToken(), value]);
  if (!row) return { ok: false, status: 409, error: 'Este pedido já foi pago pelo link.' };
  if (prev && prev.amount !== value && prev.mp_payment_id) await cancelPayment(prev.mp_payment_id);
  return { ok: true, url: siteUrl(`/pagar/${row.token}`), amount: value };
}

export type PayLinkView = {
  state: 'aberto' | 'pago' | 'cancelado';
  amount: number;
  order: { number: number; customer: string; product: string; qty: number; unit_price: number; delivery_date: string | null };
  pix: { code: string; qr_base64: string; expires_in: number; expired: boolean } | null;
};

/** O que a página pública mostra. Só lê: abrir o link (inclusive a prévia do WhatsApp) nunca gera Pix. */
export async function payLinkView(token: string): Promise<PayLinkView | null> {
  if (!isPayToken(token)) return null;
  const r = await q1<{
    amount: number; paid_at: Date | null; pix_qr_code: string; pix_qr_base64: string; pix_expires_at: Date | null;
    number: number; customer: string; product: string; qty: number; unit_price: number; status: string; payment: string; delivery_date: string | null;
  }>(
    `SELECT l.amount::float8 AS amount, l.paid_at, l.pix_qr_code, l.pix_qr_base64, l.pix_expires_at,
            o.number, o.customer, o.product, o.qty::float8 AS qty, o.unit_price::float8 AS unit_price, o.status, o.payment, o.delivery_date
       FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.token = $1`, [token]);
  if (!r) return null;
  const state = r.paid_at || r.payment === 'pago' ? 'pago' : r.status === 'cancelado' ? 'cancelado' : 'aberto';
  const left = r.pix_expires_at ? Math.max(0, Math.floor((r.pix_expires_at.getTime() - Date.now()) / 1000)) : 0;
  return {
    state, amount: r.amount,
    order: { number: r.number, customer: r.customer, product: r.product, qty: r.qty, unit_price: r.unit_price, delivery_date: r.delivery_date },
    pix: r.pix_qr_code ? { code: r.pix_qr_code, qr_base64: r.pix_qr_base64, expires_in: left, expired: left <= 0 } : null,
  };
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `cd backend && TEST_DATABASE_URL="$TEST_DATABASE_URL" npx vitest run tests/pay-link.integration.test.ts && npx tsc --noEmit`
Expected: todos os testes desta tarefa passam; tsc sem erros.

- [ ] **Step 5: Checkpoint** — `TEST_DATABASE_URL="$TEST_DATABASE_URL" npm test` (suíte inteira verde).

---

### Task 3: Gerar/renovar o Pix do link e confirmar o pagamento (`payLinkPix`, `markLinkPaid`, `handleLinkPayment`)

**Files:**
- Modify: `backend/lib/pay-link.ts`
- Modify (testes): `backend/tests/pay-link.integration.test.ts` (no marcador `// (testes das próximas tarefas entram aqui)`)

**Interfaces:**
- Consumes (Task 2): `isPayToken`, `payerEmail`, `LINK_REF`, `PayLinkView`.
- Consumes: `MAX_PIX_ATTEMPTS` de `./pix`; `createPixPayment`, `getPayment`, `pixExpiresMinutes`, `type MpPayment` de `./mercadopago`; `q`, `tx` de `./db`.
- Produces (Task 4):
  - `type PayLinkPixResult = { ok: true; state: 'paid' } | { ok: true; state: 'active' | 'renewed'; pix: { code: string; qr_base64: string; expires_in: number; expired: false } } | { ok: false; status: number; error: string }`
  - `payLinkPix(token: string): Promise<PayLinkPixResult>`
  - `markLinkPaid(linkId: number, mpPaymentId: string, paidAmount: number): Promise<number | null>` — devolve o `order_id` marcado, ou `null` se não se aplica
  - `handleLinkPayment(pay: MpPayment, linkId: number): Promise<{ ok: true; status: string; paid?: boolean }>`

- [ ] **Step 1: Escrever os testes que falham** (substituir o marcador por este bloco, mantendo o marcador no fim)

```ts
  /** link com valor `amount`; devolve ids e o token */
  async function seedLink(amount: number | null = null, o: Parameters<typeof seed>[0] = {}) {
    const order = await seed(o);
    const token = (((await L.ensurePayLink(owner, order, amount)) as any).url as string).split('/').pop()!;
    const link = (await q1<{ id: number }>('SELECT id FROM order_pay_links WHERE token = $1', [token]))!.id;
    return { order, token, link };
  }
  let pixN = 0;
  const fakePix = () => createPixPayment.mockImplementation(async (o: any) => {
    pixN++;
    return { paymentId: `p${pixN}`, code: `CODE${pixN}`, qrBase64: 'QR', expiresAt: o.expiresAt };
  });

  describe('payLinkPix', () => {
    it('GET (payLinkView) não gera Pix; o primeiro POST gera, com referência, valor e e-mail do link', async () => {
      fakePix();
      const { order, token, link } = await seedLink(50);
      await L.payLinkView(token);
      expect(createPixPayment).not.toHaveBeenCalled();
      const r: any = await L.payLinkPix(token);
      expect(r).toMatchObject({ ok: true, state: 'renewed', pix: { qr_base64: 'QR', expired: false } });
      expect(r.pix.expires_in).toBeGreaterThan(59 * 60 - 5);
      expect(createPixPayment.mock.calls[0][0]).toMatchObject({
        orderId: link, reference: `link:${link}`, total: 50, name: 'Ana', code: expect.any(Number), email: L.payerEmail(), attempt: 1,
      });
      const row = (await q1<any>('SELECT pix_attempts, mp_payment_ids, mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!;
      expect(row).toMatchObject({ pix_attempts: 1, mp_payment_ids: [row.mp_payment_id] });
      expect((await L.payLinkView(token))!.pix).toMatchObject({ code: r.pix.code, expired: false });
      void order;
    });

    it('Pix ainda válido: o segundo POST (duplo toque) devolve o mesmo e não cria outro', async () => {
      fakePix();
      const { token } = await seedLink();
      const a: any = await L.payLinkPix(token);
      const b: any = await L.payLinkPix(token);
      expect(b).toMatchObject({ ok: true, state: 'active' });
      expect(b.pix.code).toBe(a.pix.code);
      expect(createPixPayment).toHaveBeenCalledTimes(1);
    });

    it('Pix vencido: confere o antigo no Mercado Pago, cancela se pendente e gera outro (tentativa 2)', async () => {
      fakePix(); getPayment.mockResolvedValue({ status: 'pending' });
      const { token, link } = await seedLink();
      const a: any = await L.payLinkPix(token);
      const oldId = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id;
      await q(`UPDATE order_pay_links SET pix_expires_at = now() - interval '1 minute' WHERE id = $1`, [link]);
      const b: any = await L.payLinkPix(token);
      expect(b.state).toBe('renewed');
      expect(b.pix.code).not.toBe(a.pix.code);
      expect(cancelPayment).toHaveBeenCalledWith(oldId);
      expect(createPixPayment.mock.calls[1][0]).toMatchObject({ attempt: 2 });
      expect((await q1<any>('SELECT mp_payment_ids FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_ids).toHaveLength(2);
    });

    it('Pix vencido que foi pago no último instante: marca pago e não gera outro', async () => {
      fakePix();
      const { order, token, link } = await seedLink();
      await L.payLinkPix(token);
      const oldId = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id;
      await q(`UPDATE order_pay_links SET pix_expires_at = now() - interval '1 minute' WHERE id = $1`, [link]);
      getPayment.mockResolvedValue({ id: Number(oldId.slice(1)), status: 'approved', transaction_amount: 80 });
      expect(await L.payLinkPix(token)).toEqual({ ok: true, state: 'paid' });
      expect(createPixPayment).toHaveBeenCalledTimes(1);
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pago');
    });

    it('limite de Pix por link: 429', async () => {
      fakePix(); getPayment.mockResolvedValue({ status: 'pending' });
      const { token, link } = await seedLink();
      await q(`UPDATE order_pay_links SET pix_attempts = 10, pix_expires_at = now() - interval '1 minute', pix_qr_code = 'X' WHERE id = $1`, [link]);
      expect(await L.payLinkPix(token)).toMatchObject({ ok: false, status: 429 });
    });

    it('pedido pago à mão (dinheiro) depois de compartilhar: "paid", sem Pix; cancelado: 409; token ruim: 404; MP desligado: 503', async () => {
      fakePix();
      const paid = await seedLink();
      await q(`UPDATE orders SET payment = 'pago' WHERE id = $1`, [paid.order]);
      expect(await L.payLinkPix(paid.token)).toEqual({ ok: true, state: 'paid' });
      const canceled = await seedLink();
      await q(`UPDATE orders SET status = 'cancelado' WHERE id = $1`, [canceled.order]);
      expect(await L.payLinkPix(canceled.token)).toMatchObject({ ok: false, status: 409 });
      expect(await L.payLinkPix('A'.repeat(43))).toMatchObject({ ok: false, status: 404 });
      expect(await L.payLinkPix('curto')).toMatchObject({ ok: false, status: 404 });
      mpOn.mockReturnValue(false);
      expect(await L.payLinkPix((await seedLink()).token)).toMatchObject({ ok: false, status: 503 });
      expect(createPixPayment).not.toHaveBeenCalled();
    });

    it('falha do Mercado Pago ao criar o Pix: 502 e nada gravado', async () => {
      createPixPayment.mockRejectedValue(new Error('MP fora do ar'));
      const { token, link } = await seedLink();
      vi.spyOn(console, 'error').mockImplementation(() => {});
      expect(await L.payLinkPix(token)).toMatchObject({ ok: false, status: 502 });
      expect((await q1<any>('SELECT pix_attempts, pix_qr_code FROM order_pay_links WHERE id = $1', [link]))).toMatchObject({ pix_attempts: 0, pix_qr_code: '' });
    });

    it('mudar o valor depois de gerar o Pix cancela o pendente e a página volta a pedir "Pagar com Pix"', async () => {
      fakePix();
      const { order, token, link } = await seedLink(80);
      await L.payLinkPix(token);
      const oldId = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id;
      expect(await L.ensurePayLink(owner, order, 50)).toMatchObject({ ok: true, amount: 50 });
      expect(cancelPayment).toHaveBeenCalledWith(oldId);
      expect((await L.payLinkView(token))).toMatchObject({ amount: 50, pix: null });
      // mesmo valor de novo: não mexe no Pix
      await L.payLinkPix(token);
      cancelPayment.mockClear();
      await L.ensurePayLink(owner, order, 50);
      expect(cancelPayment).not.toHaveBeenCalled();
    });

    it('e-mail do pagador: padrão pelo domínio do site, ou o do .env', () => {
      const old = process.env.PAY_LINK_PAYER_EMAIL;
      delete process.env.PAY_LINK_PAYER_EMAIL;
      expect(L.payerEmail()).toMatch(/^pagamentos@[a-z0-9.-]+\.[a-z]+$/);
      expect(L.payerEmail()).not.toContain('www.');
      process.env.PAY_LINK_PAYER_EMAIL = 'loja@exemplo.com';
      expect(L.payerEmail()).toBe('loja@exemplo.com');
      if (old === undefined) delete process.env.PAY_LINK_PAYER_EMAIL; else process.env.PAY_LINK_PAYER_EMAIL = old;
    });
  });

  describe('markLinkPaid / handleLinkPayment', () => {
    async function withPix(amount: number | null = null) {
      fakePix();
      const s = await seedLink(amount);
      await L.payLinkPix(s.token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [s.link]))!.mp_payment_id as string;
      return { ...s, mp };
    }
    const approved = (mp: string, amount: number, extra: object = {}) => ({ id: Number(mp.slice(1)), status: 'approved', transaction_amount: amount, external_reference: '', ...extra }) as any;

    it('pagamento aprovado do próprio link com valor suficiente: marca o link e o pedido; a repetição não faz nada', async () => {
      const { order, link, mp } = await withPix(80);
      expect(await L.markLinkPaid(link, mp, 80)).toBe(order);
      const l = (await q1<any>('SELECT paid_at, paid_mp_id FROM order_pay_links WHERE id = $1', [link]))!;
      expect(l.paid_at).toBeTruthy(); expect(l.paid_mp_id).toBe(mp);
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pago');
      expect(await L.markLinkPaid(link, mp, 80)).toBeNull();
    });

    it('valor menor que o cobrado ou pagamento de outro link: não marca', async () => {
      const a = await withPix(80), b = await withPix(80);
      expect(await L.markLinkPaid(a.link, a.mp, 79.99)).toBeNull();
      expect(await L.markLinkPaid(a.link, b.mp, 80)).toBeNull();
      expect((await q1<any>('SELECT paid_at FROM order_pay_links WHERE id = $1', [a.link]))!.paid_at).toBeNull();
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [a.order]))!.payment).toBe('pendente');
    });

    it('valor acima do cobrado (pagou a mais) é aceito', async () => {
      const { link, mp } = await withPix(50);
      expect(await L.markLinkPaid(link, mp, 80)).not.toBeNull();
    });

    it('handleLinkPayment: aprovado marca; estornado/pendente é ignorado; id ruim não quebra; sem efeito vira aviso no log', async () => {
      const { order, link, mp } = await withPix(80);
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      expect(await L.handleLinkPayment({ ...approved(mp, 80), status: 'refunded' }, link)).toMatchObject({ ok: true, status: 'refunded' });
      expect(await L.handleLinkPayment({ ...approved(mp, 80), status: 'pending' }, link)).toMatchObject({ ok: true, status: 'pending' });
      expect(await L.handleLinkPayment(approved(mp, 80), Number.NaN)).toMatchObject({ ok: true });
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pendente');
      expect(await L.handleLinkPayment(approved(mp, 79), link)).toMatchObject({ ok: true, paid: false });
      expect(warn).toHaveBeenCalled();
      expect(await L.handleLinkPayment(approved(mp, 80), link)).toMatchObject({ ok: true, paid: true });
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pago');
      expect(await L.handleLinkPayment(approved(mp, 80), link)).toMatchObject({ ok: true, paid: false }); // reenvio do webhook
    });
  });

  // (testes das próximas tarefas entram aqui)
```
(Remover o marcador antigo; o marcador final fica uma vez só.)

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && TEST_DATABASE_URL="$TEST_DATABASE_URL" npx vitest run tests/pay-link.integration.test.ts`
Expected: FALHA — `L.payLinkPix is not a function` (e afins).

- [ ] **Step 3: Implementar** — em `backend/lib/pay-link.ts`, trocar as importações do topo por:
```ts
import { randomBytes } from 'node:crypto';
import { q, q1, tx } from './db';
import { cancelPayment, createPixPayment, getPayment, mpConfigured, pixExpiresMinutes, type MpPayment } from './mercadopago';
import { MAX_PIX_ATTEMPTS } from './pix';
import { siteUrl } from './site';
```
e acrescentar no fim do arquivo:
```ts
export type PayLinkPixResult =
  | { ok: true; state: 'paid' }
  | { ok: true; state: 'active' | 'renewed'; pix: { code: string; qr_base64: string; expires_in: number; expired: false } }
  | { ok: false; status: number; error: string };

/** Marca o link e o pedido como pagos. Só vale para pagamento gerado para este link e que cobre o valor cobrado.
 *  Devolve o id do pedido marcado, ou null se não se aplica (já pago, pagamento de outro link, valor menor). */
export async function markLinkPaid(linkId: number, mpPaymentId: string, paidAmount: number): Promise<number | null> {
  return tx(async (c) => {
    const { rows } = await c.query(
      `UPDATE order_pay_links SET paid_at = now(), paid_mp_id = $2
        WHERE id = $1 AND $2 = ANY(mp_payment_ids) AND paid_at IS NULL AND $3::numeric >= amount RETURNING order_id`,
      [linkId, mpPaymentId, paidAmount]);
    if (!rows[0]) return null;
    await c.query(`UPDATE orders SET payment = 'pago' WHERE id = $1`, [rows[0].order_id]);
    return rows[0].order_id as number;
  });
}

/** Pagamento confirmado pelo webhook (external_reference "link:<id>"). Estorno e outros estados são ignorados: a devolução, se houver, é no painel do Mercado Pago. */
export async function handleLinkPayment(pay: MpPayment, linkId: number): Promise<{ ok: true; status: string; paid?: boolean }> {
  if (!Number.isInteger(linkId) || pay.status !== 'approved') return { ok: true, status: pay.status };
  const orderId = await markLinkPaid(linkId, String(pay.id), pay.transaction_amount);
  if (orderId == null) console.warn('[pagamento por link] pagamento aprovado que não foi aplicado (já pago, de outro link ou valor menor que o cobrado)', { linkId, payment: pay.id, amount: pay.transaction_amount });
  return { ok: true, status: pay.status, paid: orderId != null };
}

/** "Pagar com Pix" da página pública: gera o Pix (primeiro ou novo). Ainda válido, devolve o mesmo; vencido, confere o antigo no Mercado Pago antes de trocar. */
export async function payLinkPix(token: string): Promise<PayLinkPixResult> {
  if (!isPayToken(token)) return { ok: false, status: 404, error: 'Link não encontrado.' };
  if (!mpConfigured()) return { ok: false, status: 503, error: 'O Pix não está disponível agora. Tente de novo mais tarde.' };
  const l = await q1<{
    id: number; amount: number; paid_at: Date | null; mp_payment_id: string | null; pix_qr_code: string; pix_qr_base64: string; pix_expires_at: Date | null;
    pix_attempts: number; number: number; customer: string; status: string; payment: string;
  }>(
    `SELECT l.id, l.amount::float8 AS amount, l.paid_at, l.mp_payment_id, l.pix_qr_code, l.pix_qr_base64, l.pix_expires_at, l.pix_attempts,
            o.number, o.customer, o.status, o.payment
       FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.token = $1`, [token]);
  if (!l) return { ok: false, status: 404, error: 'Link não encontrado.' };
  if (l.paid_at || l.payment === 'pago') return { ok: true, state: 'paid' };
  if (l.status === 'cancelado') return { ok: false, status: 409, error: 'Este pedido foi cancelado.' };

  const left = l.pix_expires_at ? l.pix_expires_at.getTime() - Date.now() : 0;
  if (l.pix_qr_code && left > 0) {
    return { ok: true, state: 'active', pix: { code: l.pix_qr_code, qr_base64: l.pix_qr_base64, expires_in: Math.floor(left / 1000), expired: false } };
  }
  if (l.pix_attempts >= MAX_PIX_ATTEMPTS) return { ok: false, status: 429, error: 'Muitos Pix gerados para este pedido. Fale com a gente.' };

  // o Pix anterior pode ter sido pago no último instante: confere no Mercado Pago antes de trocar
  if (l.mp_payment_id) {
    try {
      const old = await getPayment(l.mp_payment_id);
      if (old.status === 'approved' && (await markLinkPaid(l.id, l.mp_payment_id, old.transaction_amount)) != null) return { ok: true, state: 'paid' };
      if (old.status === 'pending' || old.status === 'in_process') await cancelPayment(l.mp_payment_id);
    } catch { /* sem resposta: o Pix já venceu; segue para o novo */ }
  }

  const attempt = l.pix_attempts + 1;
  const expiresAt = new Date(Date.now() + pixExpiresMinutes() * 60_000);
  try {
    const pay = await createPixPayment({
      orderId: l.id, reference: `${LINK_REF}${l.id}`, code: l.number, total: l.amount, email: payerEmail(), name: l.customer, attempt, expiresAt,
    });
    await q(
      `UPDATE order_pay_links SET mp_payment_id = $2, pix_qr_code = $3, pix_qr_base64 = $4, pix_expires_at = $5, pix_attempts = $6,
         mp_payment_ids = array_append(mp_payment_ids, $2) WHERE id = $1`,
      [l.id, pay.paymentId, pay.code, pay.qrBase64, expiresAt, attempt]);
    return { ok: true, state: 'renewed', pix: { code: pay.code, qr_base64: pay.qrBase64, expires_in: Math.floor((expiresAt.getTime() - Date.now()) / 1000), expired: false } };
  } catch (e) {
    console.error('[mercadopago] falha ao gerar Pix do link de pagamento', l.id, e);
    return { ok: false, status: 502, error: 'Não conseguimos gerar o Pix agora. Tente de novo em instantes.' };
  }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `cd backend && TEST_DATABASE_URL="$TEST_DATABASE_URL" npx vitest run tests/pay-link.integration.test.ts && npx tsc --noEmit`
Expected: tudo verde. Se o teste "Pix vencido pago no último instante" falhar por causa do `id` numérico, conferir que `fakePix` gera `p<N>` e o teste usa `Number(oldId.slice(1))`.

- [ ] **Step 5: Checkpoint** — `TEST_DATABASE_URL="$TEST_DATABASE_URL" npm test` (suíte inteira verde).

---

### Task 4: Rotas HTTP (gestão e públicas) e ramo do webhook

**Files:**
- Create: `backend/app/api/orders/[id]/pagamento/route.ts`
- Create: `backend/app/api/pagar/[token]/route.ts`
- Create: `backend/app/api/pagar/[token]/pix/route.ts`
- Modify: `backend/app/api/webhooks/mercadopago/route.ts` (ramo `link:` antes do tratamento de pedido da loja)
- Modify (testes): `backend/tests/pay-link.integration.test.ts`

**Interfaces:**
- Consumes (Tasks 2–3): `ensurePayLink`, `payLinkView`, `payLinkPix`, `handleLinkPayment`, `LINK_REF`.
- Produces (usado por frontend e app): `POST /api/orders/:id/pagamento` `{amount?: string|number}` → `200 {url, amount}`; `GET /api/pagar/:token` → `200 PayLinkView` ou `404`; `POST /api/pagar/:token/pix` → `200 PayLinkPixResult` (corpo com `ok`, `state`, `pix`) ou erro `{error}` com o status do resultado.

- [ ] **Step 1: Escrever os testes que falham** (no marcador)

```ts
  describe('rotas HTTP', () => {
    const ctx = (p: Record<string, string>) => ({ params: Promise.resolve(p) }) as any;
    const post = (body?: unknown) => new Request('http://x/api', { method: 'POST', body: body == null ? undefined : JSON.stringify(body) });

    it('POST /api/orders/:id/pagamento: devolve url e valor; aceita texto pt-BR; recusa valor ruim e pedido alheio', async () => {
      const R = await import('@/app/api/orders/[id]/pagamento/route');
      const id = await seed();
      const ok = await R.POST(post({ amount: '30,5' }), ctx({ id: String(id) }));
      expect(ok.status).toBe(200);
      expect(await ok.json()).toMatchObject({ amount: 30.5, url: expect.stringMatching(/\/pagar\/[A-Za-z0-9_-]{43}$/) });
      expect((await R.POST(post({}), ctx({ id: String(id) }))).status).toBe(200); // sem valor: total
      expect((await R.POST(post({ amount: 'abc' }), ctx({ id: String(id) }))).status).toBe(400);
      expect((await R.POST(post({ amount: '80,01' }), ctx({ id: String(id) }))).status).toBe(400);
      expect((await R.POST(post({ amount: '-5' }), ctx({ id: String(id) }))).status).toBe(400);
      expect((await R.POST(post({}), ctx({ id: String(await seed({ user: other })) }))).status).toBe(404);
      expect((await R.POST(post({}), ctx({ id: 'x' }))).status).toBe(404);
    });

    it('rotas públicas: GET resume sem gerar Pix; POST gera; token ruim é 404', async () => {
      fakePix();
      const G = await import('@/app/api/pagar/[token]/route');
      const P = await import('@/app/api/pagar/[token]/pix/route');
      const { token } = await seedLink(40);
      const g = await G.GET(new Request('http://x/api'), ctx({ token }));
      expect(g.status).toBe(200);
      expect(await g.json()).toMatchObject({ state: 'aberto', amount: 40, pix: null });
      expect(createPixPayment).not.toHaveBeenCalled();
      const p = await P.POST(post(), ctx({ token }));
      expect(p.status).toBe(200);
      expect(await p.json()).toMatchObject({ ok: true, state: 'renewed', pix: { code: expect.any(String) } });
      expect((await G.GET(new Request('http://x/api'), ctx({ token: 'A'.repeat(43) }))).status).toBe(404);
      expect((await P.POST(post(), ctx({ token: 'A'.repeat(43) }))).status).toBe(404);
      const bad = await P.POST(post(), ctx({ token: 'curto' }));
      expect(bad.status).toBe(404);
      expect((await bad.json()).error).toBeTruthy();
    });
  });

  describe('webhook do Mercado Pago com link de pagamento', () => {
    const hook = (id: string) => new Request('http://x/api/webhooks/mercadopago', { method: 'POST', body: JSON.stringify({ type: 'payment', data: { id } }) });
    beforeEach(() => { process.env.MP_WEBHOOK_SECRET = 's'; process.env.MP_ACCESS_TOKEN = 't'; });

    it('aprovado: marca o pedido do link como pago; o reenvio da notificação não repete nada', async () => {
      fakePix();
      const W = await import('@/app/api/webhooks/mercadopago/route');
      const { order, token, link } = await seedLink(80);
      await L.payLinkPix(token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id as string;
      getPayment.mockResolvedValue({ id: Number(mp.slice(1)), status: 'approved', transaction_amount: 80, external_reference: `link:${link}` });
      expect((await W.POST(hook(mp.slice(1)))).status).toBe(200);
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pago');
      const first = (await q1<any>('SELECT paid_at FROM order_pay_links WHERE id = $1', [link]))!.paid_at.getTime();
      expect((await W.POST(hook(mp.slice(1)))).status).toBe(200);
      expect((await q1<any>('SELECT paid_at FROM order_pay_links WHERE id = $1', [link]))!.paid_at.getTime()).toBe(first);
      expect((await L.payLinkView(token))!.state).toBe('pago');
    });

    it('valor menor que o cobrado, pendente ou estornado: responde 200 mas não marca pago', async () => {
      fakePix();
      const W = await import('@/app/api/webhooks/mercadopago/route');
      const { order, token, link } = await seedLink(80);
      await L.payLinkPix(token);
      const mp = (await q1<any>('SELECT mp_payment_id FROM order_pay_links WHERE id = $1', [link]))!.mp_payment_id as string;
      vi.spyOn(console, 'warn').mockImplementation(() => {});
      for (const pay of [{ status: 'approved', transaction_amount: 79 }, { status: 'pending', transaction_amount: 80 }, { status: 'refunded', transaction_amount: 80 }]) {
        getPayment.mockResolvedValue({ id: Number(mp.slice(1)), external_reference: `link:${link}`, ...pay });
        expect((await W.POST(hook(mp.slice(1)))).status).toBe(200);
      }
      expect((await q1<any>('SELECT payment FROM orders WHERE id = $1', [order]))!.payment).toBe('pendente');
    });
  });

  // (testes das próximas tarefas entram aqui)
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && TEST_DATABASE_URL="$TEST_DATABASE_URL" npx vitest run tests/pay-link.integration.test.ts`
Expected: FALHA — `Failed to resolve import "@/app/api/orders/[id]/pagamento/route"`.

- [ ] **Step 3: Implementar as três rotas**

`backend/app/api/orders/[id]/pagamento/route.ts`:
```ts
import { fail, num, ok, route } from '@/lib/api';
import { ensurePayLink } from '@/lib/pay-link';

// Link de pagamento Pix de um pedido registrado à mão. `amount` (opcional, texto pt-BR ou número) é o valor a cobrar: o padrão é o total.
export const POST = route(async (req, user, { id }) => {
  const orderId = Number(id);
  if (!Number.isInteger(orderId)) return fail('Pedido não encontrado.', 404);
  const b = await req.json().catch(() => ({}));
  const amount = b?.amount == null || String(b.amount).trim() === '' ? null : num(b.amount);
  const r = await ensurePayLink(user.id, orderId, amount);
  return r.ok ? ok({ url: r.url, amount: r.amount }) : fail(r.error, r.status);
});
```
`backend/app/api/pagar/[token]/route.ts`:
```ts
import { fail, ok } from '@/lib/api';
import { payLinkView } from '@/lib/pay-link';

// Página pública do link de pagamento (sem sessão). Só leitura: abrir o link, inclusive a prévia do WhatsApp, nunca gera Pix.
export async function GET(_req: Request, ctx: { params: Promise<{ token: string }> }) {
  const { token } = await ctx.params;
  try {
    const v = await payLinkView(token);
    return v ? ok(v) : fail('Link não encontrado.', 404);
  } catch (e) {
    console.error(e);
    return fail('Algo deu errado. Tente de novo em instantes.', 500);
  }
}
```
`backend/app/api/pagar/[token]/pix/route.ts`:
```ts
import { fail, ok } from '@/lib/api';
import { payLinkPix } from '@/lib/pay-link';

// "Pagar com Pix" / "Gerar novo Pix" da página pública do link de pagamento.
export async function POST(_req: Request, ctx: { params: Promise<{ token: string }> }) {
  const { token } = await ctx.params;
  try {
    const r = await payLinkPix(token);
    return r.ok ? ok(r) : fail(r.error, r.status);
  } catch (e) {
    console.error(e);
    return fail('Algo deu errado. Tente de novo em instantes.', 500);
  }
}
```

- [ ] **Step 4: Ramo do webhook**

Em `backend/app/api/webhooks/mercadopago/route.ts`: acrescentar o import
```ts
import { handleLinkPayment, LINK_REF } from '@/lib/pay-link';
```
e, logo depois de `const pay = await getPayment(dataId); // nunca confia no corpo da notificação`, antes de `const orderId = Number(pay.external_reference);`:
```ts
    // link de pagamento de pedido registrado à mão: tratador próprio (não toca em shop_orders nem em devoluções)
    const ref = String(pay.external_reference ?? '');
    if (ref.startsWith(LINK_REF)) return Response.json(await handleLinkPayment(pay, Number(ref.slice(LINK_REF.length))));
```

- [ ] **Step 5: Rodar e ver passar**

Run: `cd backend && TEST_DATABASE_URL="$TEST_DATABASE_URL" npm test && npx tsc --noEmit`
Expected: suíte inteira verde (incluindo `mercadopago.test.ts`, que cobre o webhook da loja) e tsc sem erros.

- [ ] **Step 6: Build de teste do backend (nunca sobre o `.next` de produção)**

Run: `cd backend && NEXT_DIST_DIR=.next-test npm run build && rm -rf .next-test`
Expected: build conclui; rotas `/api/pagar/[token]`, `/api/pagar/[token]/pix` e `/api/orders/[id]/pagamento` listadas.

---

### Task 5: Página pública `/pagar/[token]` (frontend)

**Files:**
- Modify: `shared/types.ts` (tipo `PayLinkView`)
- Modify: `frontend/components/PixCopy.tsx` (props `renewUrl` e `refundNote`)
- Create: `frontend/app/pagar/layout.tsx`
- Create: `frontend/app/pagar/[token]/page.tsx`
- Create: `frontend/app/pagar/[token]/PayLink.tsx`

**Interfaces:**
- Consumes (Task 4): `GET /api/pagar/:token` (`PayLinkView`), `POST /api/pagar/:token/pix`.
- Produces: `PixCopy` com `Props = { orderId?: number; renewUrl?: string; refundNote?: boolean; code: string; qr: string; expiresIn: number; expired: boolean }` (os chamadores atuais continuam iguais).

- [ ] **Step 1: Tipo compartilhado** — acrescentar ao fim de `shared/types.ts`:
```ts
/** Página pública do link de pagamento de pedido registrado à mão (`GET /api/pagar/:token`). */
export type PayLinkView = {
  state: 'aberto' | 'pago' | 'cancelado';
  amount: number;
  order: { number: number; customer: string; product: string; qty: number; unit_price: number; delivery_date: string | null };
  pix: { code: string; qr_base64: string; expires_in: number; expired: boolean } | null;
};
```

- [ ] **Step 2: `PixCopy` aceita endereço de renovação e esconde a frase de devolução**

Em `frontend/components/PixCopy.tsx`:
- Trocar a linha `type Props = ...` por:
```tsx
type Props = { orderId?: number; renewUrl?: string; refundNote?: boolean; code: string; qr: string; expiresIn: number; expired: boolean };
```
- Trocar a assinatura: `export function PixCopy({ orderId, renewUrl, refundNote = true, code, qr, expiresIn, expired }: Props) {`
- Em `renew()`, trocar a chamada por:
```tsx
    try { await api(renewUrl ?? `/api/cliente/pedidos/${orderId}/pix`, 'POST'); router.refresh(); }
```
- No parágrafo "Assim que o pagamento for confirmado…", trocar o texto por:
```tsx
      <p className="mt-1 text-sm text-muted">Assim que o pagamento for confirmado, o pedido é marcado como pago automaticamente.{refundNote && ' Cancelou antes de começarmos a produzir? Devolvemos o Pix na hora.'}</p>
```

- [ ] **Step 3: Layout público**

`frontend/app/pagar/layout.tsx`:
```tsx
import Link from 'next/link';
import type { Metadata } from 'next';
import { Logo } from '@/components/Logo';

export const dynamic = 'force-dynamic';
export const metadata: Metadata = { title: 'Pagar pedido', robots: { index: false, follow: false } };

export default function PagarLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="brand-scope min-h-dvh">
      <header className="border-b border-line bg-rk-claro/90">
        <div className="mx-auto flex max-w-2xl items-center px-4 py-2.5">
          <Link href="/" aria-label="Raykookie, página inicial"><Logo variant="compacta" tone="claro" imgClassName="h-10" /></Link>
        </div>
      </header>
      <main className="mx-auto max-w-2xl space-y-4 px-4 py-6">{children}</main>
    </div>
  );
}
```

- [ ] **Step 4: Página (servidor)**

`frontend/app/pagar/[token]/page.tsx`:
```tsx
import { notFound } from 'next/navigation';
import { apiPublic } from '@/lib/server-api';
import type { PayLinkView } from '@shared/types';
import { PayLink } from './PayLink';

export default async function Page({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  const view = await apiPublic<PayLinkView>(`/api/pagar/${encodeURIComponent(token)}`, 0); // 0 = sem cache
  if (!view) notFound();
  return <PayLink token={token} view={view} />;
}
```

- [ ] **Step 5: Parte interativa**

`frontend/app/pagar/[token]/PayLink.tsx`:
```tsx
'use client';
import { CheckCircle2, QrCode } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { PixCopy } from '@/components/PixCopy';
import { api } from '@/lib/client';
import type { PayLinkView } from '@shared/types';

const brl = (n: number) => new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(n);

export function PayLink({ token, view }: { token: string; view: PayLinkView }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const url = `/api/pagar/${encodeURIComponent(token)}`;
  const waiting = view.state === 'aberto' && !!view.pix;

  // com o Pix na tela, confere o pagamento a cada 5 s e recarrega quando o estado muda
  useEffect(() => {
    if (!waiting) return;
    const id = setInterval(async () => {
      try {
        const r = await fetch(url, { cache: 'no-store' });
        if (r.ok && (await r.json()).state !== 'aberto') router.refresh();
      } catch { /* sem conexão: tenta de novo no próximo ciclo */ }
    }, 5000);
    return () => clearInterval(id);
  }, [waiting, url, router]);

  async function start() {
    setBusy(true); setError('');
    try { await api(`${url}/pix`, 'POST'); router.refresh(); }
    catch (e: any) { setError(e.message); }
    finally { setBusy(false); }
  }

  const o = view.order;
  return (
    <>
      <h1 className="font-display text-3xl font-semibold leading-tight">Pedido #{o.number}</h1>
      <section className="rounded-2xl border border-line bg-surface p-4" aria-label="Resumo do pedido">
        <p className="text-sm text-muted">Para {o.customer}</p>
        <p className="mt-1 font-semibold">{o.qty} × {o.product}</p>
        {o.delivery_date && <p className="text-sm text-muted">Entrega em {o.delivery_date.split('-').reverse().join('/')}</p>}
        <p className="mt-3 text-sm text-muted">Valor a pagar</p>
        <p className="font-display text-3xl font-semibold tnum">{brl(view.amount)}</p>
      </section>

      {view.state === 'pago' && (
        <p role="status" className="flex items-center gap-2 rounded-2xl border border-ok/40 bg-ok/10 p-4 font-semibold text-ok"><CheckCircle2 size={20} />Pagamento confirmado. Obrigada!</p>
      )}
      {view.state === 'cancelado' && (
        <p role="status" className="rounded-2xl border border-line bg-surface-2 p-4 text-muted">Este pedido foi cancelado, então não há nada a pagar. Fale com a gente se precisar.</p>
      )}
      {view.state === 'aberto' && !view.pix && (
        <button type="button" onClick={start} disabled={busy} className="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-pink px-4 font-semibold text-on-pink hover:bg-pink-strong disabled:opacity-60">
          <QrCode size={18} />{busy ? 'Gerando o Pix…' : 'Pagar com Pix'}
        </button>
      )}
      {view.state === 'aberto' && view.pix && (
        <PixCopy renewUrl={`${url}/pix`} refundNote={false} code={view.pix.code} qr={view.pix.qr_base64} expiresIn={view.pix.expires_in} expired={view.pix.expired} />
      )}
      {error && <p role="alert" className="text-sm font-semibold text-bad">{error}</p>}
    </>
  );
}
```

- [ ] **Step 6: Verificar tipos e build de teste (nunca sobre o `.next` de produção)**

Run:
```bash
cd /home/g_snts/works/rayka-estoque/frontend && npx tsc --noEmit && NEXT_DIST_DIR=.next-test npm run build && rm -rf .next-test
```
Expected: sem erros; build lista a rota `/pagar/[token]`. Se o nome de alguma classe de cor (`text-ok`, `bg-ok/10`, `border-ok/40`) não existir no Tailwind do projeto, trocar por `text-pink-strong`/`bg-pink-tint` (as usadas em `PixCopy`).

- [ ] **Step 7: Checkpoint de ponta a ponta no navegador (sem tocar em produção)**

Subir backend de dev e frontend de dev **em portas próprias**, apontando para o banco de TESTE e para um Mercado Pago de mentira:
```bash
cd /home/g_snts/works/rayka-estoque/backend  && DATABASE_URL="$TEST_DATABASE_URL" PORT=10096 npx next dev -p 10096 &   # guardar o PID
cd /home/g_snts/works/rayka-estoque/frontend && BACKEND_URL=http://127.0.0.1:10096 npx next dev -p 10097 &          # guardar o PID
```
Criar um pedido manual no banco de teste, gerar o link (`POST /api/orders/:id/pagamento` com a sessão do teste) e abrir `http://127.0.0.1:10097/pagar/<token>` conferindo: resumo e valor; botão "Pagar com Pix" (com `MP_API_BASE` apontando para o servidor de mentira dos testes E2E, ou parar aqui e confiar nos testes de backend); depois do pagamento simulado, a página vira "Pagamento confirmado". Encerrar **só os PIDs guardados** ao final. Se montar o servidor de mentira for custoso, registrar no relatório que esta conferência visual ficou para o usuário após a publicação.

---

### Task 6: App de gestão — botão e folha "Compartilhar link de pagamento"

**Files:**
- Modify: `mobile-common/lib/core/models.dart` (getter `canPayLink` em `OrderModel`)
- Create: `mobile-server/lib/features/admin/pay_link_sheet.dart`
- Modify: `mobile-server/lib/features/admin/admin_orders_screen.dart` (import, método `_payLink`, botão no card)
- Modify (testes): `mobile-server/test/widget_test.dart` (novo `group`, antes de `group('devoluções (gestão)'`)

**Interfaces:**
- Consumes (Task 4): `POST /api/orders/:id/pagamento` `{amount}` → `{url, amount}`.
- Consumes (já existem): `shareText(String text, {required String subject, required String copiedMsg})` e `sharePlatform` de `mobile-common/lib/ui/share_menu.dart`; `showFormSheet`, `AppTextField`, `BusyButton`, `NumberFormatter` de `widgets.dart`; `parseLocaleNumber`, `money` de `format.dart`; `Toast`.
- Produces: `PayLinkSheet(order: OrderModel)` (pop com `true` ao compartilhar); `OrderModel.canPayLink`.

- [ ] **Step 1: Escrever os testes que falham** — em `mobile-server/test/widget_test.dart`, inserir antes da linha `  group('devoluções (gestão)', () {`:

```dart
  group('link de pagamento (gestão)', () {
    Map<String, dynamic> order(int id, {String payment = 'pendente', String status = 'novo', int? shop}) => {
          'id': id, 'number': 12, 'order_date': '2026-10-03', 'customer': 'Ana', 'recipe_id': null, 'product': 'Bolo de cenoura', 'qty': 2, 'unit_price': 40.0,
          'delivery_date': '2026-10-10', 'status': status, 'payment': payment, 'notes': '', 'address': '', 'shop_order_id': shop,
        };
    final share = 'Compartilhar link de pagamento';

    Future<FakeBackend> open(WidgetTester t, List<Map<String, dynamic>> orders, {Map<String, Handler> extra = const {}}) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/orders': (_) => (200, orders),
        'GET /api/recipes': (_) => (200, []),
        ...extra,
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/pedidos', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      return be;
    }

    testWidgets('o botão só aparece em pedido registrado à mão, não pago e não cancelado (pendente ou sinal)', (t) async {
      await open(t, [order(5), order(6, payment: 'sinal'), order(7, payment: 'pago'), order(8, shop: 3), order(9, status: 'cancelado')]);
      expect(find.text(share, skipOffstage: false), findsNWidgets(2));
    });

    testWidgets('compartilhar: o valor editado vai para a API e a mensagem leva o link', (t) async {
      String? shared;
      final orig = sharePlatform;
      sharePlatform = (p) async => shared = p.text;
      addTearDown(() => sharePlatform = orig);
      final be = await open(t, [order(5)], extra: {
        'POST /api/orders/5/pagamento': (_) => (200, {'url': 'https://raykookie.kntap.com.br/pagar/abc', 'amount': 30.0}),
      });
      await t.tap(find.text(share));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.widgetWithText(TextField, '80,00'), findsOneWidget); // vem com o total
      await t.enterText(find.byType(TextField).last, '30,00');
      await t.pump();
      await t.tap(find.widgetWithText(FilledButton, 'Compartilhar link'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['POST /api/orders/5/pagamento'], {'amount': '30,00'});
      expect(shared, allOf(contains('pedido #12'), contains('30,00'), contains('https://raykookie.kntap.com.br/pagar/abc')));
    });

    testWidgets('valor acima do total ou vazio não chama a API', (t) async {
      final be = await open(t, [order(5)], extra: {'POST /api/orders/5/pagamento': (_) => (200, {'url': 'x', 'amount': 1.0})});
      await t.tap(find.text(share));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      for (final v in ['100,00', '0', '']) {
        await t.enterText(find.byType(TextField).last, v);
        await t.pump();
        await t.tap(find.widgetWithText(FilledButton, 'Compartilhar link'));
        await t.pump(const Duration(milliseconds: 100));
      }
      expect(be.calls.where((c) => c.startsWith('POST /api/orders/5/pagamento')), isEmpty);
    });

    testWidgets('erro da API aparece e a folha continua aberta', (t) async {
      final be = await open(t, [order(5)], extra: {'POST /api/orders/5/pagamento': (_) => (409, {'error': 'Este pedido já está pago.'})});
      await t.tap(find.text(share));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.widgetWithText(FilledButton, 'Compartilhar link'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.calls, contains('POST /api/orders/5/pagamento'));
      expect(find.widgetWithText(FilledButton, 'Compartilhar link'), findsOneWidget);
    });
  });

```
(o `import 'package:raykookie_common/ui/share_menu.dart';` já existe no topo do arquivo.)

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd /home/g_snts/works/rayka-estoque/mobile-server && export PATH="$HOME/development/flutter/bin:$PATH" && flutter test test/widget_test.dart --plain-name "link de pagamento"`
Expected: FALHA (botão não existe). Se falhar antes por rota não simulada na tela Pedidos (404 em outro endpoint), acrescentar essa rota ao `FakeBackend` do helper `open` e repetir.

- [ ] **Step 3: Getter no modelo**

Em `mobile-common/lib/core/models.dart`, dentro de `OrderModel`, logo abaixo de `double get total => qty * unitPrice;`:
```dart
  /// Pedido registrado à mão (não veio do app), ainda não pago e não cancelado: dá para mandar um link de pagamento Pix.
  bool get canPayLink => shopOrderId == null && status != 'cancelado' && payment != 'pago';
```

- [ ] **Step 4: A folha**

`mobile-server/lib/features/admin/pay_link_sheet.dart`:
```dart
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/share_menu.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// Link de pagamento Pix de um pedido registrado à mão: a confeiteira confere o valor (pode ser menos que o total, se já houve sinal) e compartilha.
class PayLinkSheet extends StatefulWidget {
  const PayLinkSheet({super.key, required this.order});
  final OrderModel order;
  @override
  State<PayLinkSheet> createState() => _PayLinkSheetState();
}

class _PayLinkSheetState extends State<PayLinkSheet> {
  late final _amount = TextEditingController(text: _fmt(widget.order.total));
  bool _busy = false;

  static String _fmt(double v) => v.toStringAsFixed(2).replaceAll('.', ',');

  @override
  void dispose() {
    _amount.dispose();
    super.dispose();
  }

  /// Valor digitado (arredondado a 2 casas), ou null se vazio, zero ou acima do total do pedido.
  double? get _value {
    final v = parseLocaleNumber(_amount.text);
    if (v == null || v.isNaN || v < 0.01 || v > widget.order.total + 0.004) return null;
    return (v * 100).round() / 100;
  }

  /// Pede o link ao servidor com o valor digitado; null se o valor é inválido ou a API recusou (o aviso já foi mostrado).
  Future<({String url, double amount})?> _link() async {
    final v = _value;
    if (v == null) {
      Toast.error('Informe um valor entre R\$ 0,01 e ${money(widget.order.total)}.');
      return null;
    }
    setState(() => _busy = true);
    try {
      final r = await context.read<Session>().ownerApi.post('/api/orders/${widget.order.id}/pagamento', {'amount': _fmt(v)});
      return (url: (r as Map)['url'] as String, amount: v);
    } on ApiException catch (e) {
      Toast.error(e.message);
      return null;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _share() async {
    final l = await _link();
    if (l == null || !mounted) return;
    final o = widget.order;
    await shareText(
      'Olá! Segue o link para pagar o pedido #${o.number} (${money(l.amount)}): ${l.url}',
      subject: 'Pagamento do pedido #${o.number}',
      copiedMsg: 'Link copiado',
    );
    if (mounted) Navigator.of(context).pop(true);
  }

  Future<void> _copy() async {
    final l = await _link();
    if (l == null) return;
    await Clipboard.setData(ClipboardData(text: l.url));
    Toast.ok('Link copiado');
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final o = widget.order;
    return Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(
        'Manda para ${o.customer} um link com o Pix deste pedido. Quando ele pagar, o pedido vira "pago" sozinho.',
        style: TextStyle(color: c.muted, height: 1.4),
      ),
      const SizedBox(height: 12),
      AppTextField(
        label: 'Valor a cobrar (R\$)',
        controller: _amount,
        helper: 'Total do pedido: ${money(o.total)}. Se já recebeu um sinal, cobre só o restante.',
        keyboardType: const TextInputType.numberWithOptions(decimal: true),
        formatters: [NumberFormatter()],
      ),
      const SizedBox(height: 8),
      BusyButton(label: 'Compartilhar link', busy: _busy, onPressed: _share),
      const SizedBox(height: 8),
      SizedBox(
        width: double.infinity,
        child: OutlinedButton(onPressed: _busy ? null : _copy, style: OutlinedButton.styleFrom(minimumSize: const Size(0, 46)), child: const Text('Copiar link')),
      ),
    ]);
  }
}
```

- [ ] **Step 5: Botão no card e método**

Em `mobile-server/lib/features/admin/admin_orders_screen.dart`:
- Acrescentar o import junto de `import 'shop_order_actions.dart';`:
```dart
import 'pay_link_sheet.dart';
```
- Logo abaixo do método `_shopAction(...)`, acrescentar:
```dart
  Future<void> _payLink(OrderModel o) async {
    await showFormSheet<bool>(context, title: 'Link de pagamento · pedido #${o.number}', child: PayLinkSheet(order: o));
  }
```
- No `_orderCard`, logo depois da linha `if (o.shopOrderId != null) ..._shopBands(context, o),` (e antes de `const SizedBox(height: 8),`), acrescentar:
```dart
            if (o.canPayLink)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    onPressed: () => _payLink(o),
                    style: OutlinedButton.styleFrom(minimumSize: const Size(0, 46)),
                    icon: const Icon(Icons.ios_share_rounded, size: 18),
                    label: const Text('Compartilhar link de pagamento'),
                  ),
                ),
              ),
```

- [ ] **Step 6: Rodar e ver passar**

Run:
```bash
cd /home/g_snts/works/rayka-estoque/mobile-server && export PATH="$HOME/development/flutter/bin:$PATH"
flutter analyze && flutter test
cd ../mobile-common && flutter analyze && flutter test
```
Expected: `No issues found!` e todos os testes verdes (referência anterior: gestão 53). **Não** rodar `dart format lib` em arquivos existentes (reformata o código todo); só no arquivo novo, se quiser: `dart format lib/features/admin/pay_link_sheet.dart`.

- [ ] **Step 7: Checkpoint** — `cd /home/g_snts/works/rayka-estoque/mobile-client && flutter analyze && flutter test` (o getter novo está em `mobile-common`, compartilhado; o app do cliente precisa continuar verde).

---

### Task 7: Documentação, `.env.example`, spec e verificação final

**Files:**
- Modify: `docs/API.md`
- Modify: `backend/.env.example`
- Modify: `docs/superpowers/specs/2026-10-03-link-pagamento-design.md` (ajuste: `share_plus` já existe)

- [ ] **Step 1: `docs/API.md`** — na tabela de rotas do painel, junto de `POST /api/orders/shop/:id/cancelar`, acrescentar as linhas:
```
| `POST /api/orders/:id/pagamento` | `{amount?}`: cria (ou reaproveita) o link de pagamento Pix de um pedido registrado à mão, não pago. Devolve `{url, amount}`. `amount` (texto pt-BR ou número) vai de 0,01 ao total da linha; padrão = total |
| `GET /api/pagar/:token` | **Público.** Resumo do link: `{state: aberto\|pago\|cancelado, amount, order, pix}`. Só leitura (não gera Pix) |
| `POST /api/pagar/:token/pix` | **Público.** Gera (ou renova, se venceu) o Pix do link. Devolve `{ok, state, pix}` ou `{error}` |
```
e uma seção curta "Link de pagamento (pedido registrado à mão)" com: tabela `order_pay_links`; `external_reference = link:<id>`; webhook só marca pago com pagamento do link e valor ≥ cobrado; estorno de link ignorado; `PAY_LINK_PAYER_EMAIL`; o link vive e morre com o pedido (`ON DELETE CASCADE`).

- [ ] **Step 2: `backend/.env.example`** — depois de `MP_WEBHOOK_SECRET`/`PIX_EXPIRES_MINUTES`, acrescentar:
```
# E-mail usado como "pagador" nos Pix do link de pagamento (pedido registrado à mão). Padrão: pagamentos@<domínio do SITE_URL>
PAY_LINK_PAYER_EMAIL=
```

- [ ] **Step 3: Spec** — em `docs/superpowers/specs/2026-10-03-link-pagamento-design.md`, na seção "App de gestão", trocar "`share_plus` entra no `pubspec.yaml`." por "`share_plus` e `shareText` já existem em `mobile-common` (`ui/share_menu.dart`); não há dependência nova."

- [ ] **Step 4: Verificação completa (mesmos passos do pipeline, sem builds sobre a produção)**

Run: `cd /home/g_snts/works/rayka-estoque && TEST_DATABASE_URL="$TEST_DATABASE_URL" bash infra/check.sh --fast`
Expected: "Tudo certo." (backend tipos+testes, frontend tipos, Flutter dos três pacotes). **Não** rodar `infra/check.sh` sem `--fast`: os builds sobrescreveriam o `.next` de produção.

- [ ] **Step 5: Conferir que a produção não foi tocada**

Run: `ss -ltnp | grep :10005; ls -la --time=ctime /home/g_snts/works/rayka-estoque/backend/.next/BUILD_ID /home/g_snts/works/rayka-estoque/frontend/.next/BUILD_ID`
Expected: o mesmo PID de antes na 10005 e `BUILD_ID` com data anterior ao início do trabalho.

- [ ] **Step 6: Relatório final ao usuário** com: arquivos criados/alterados, resultado dos testes, o que ficou sem testar de ponta a ponta (Pix real no Mercado Pago, compartilhamento real no celular) e a sequência de publicação (do usuário): `cd backend && npm run migrate` → `bash infra/build.sh` → `sudo bash infra/install.sh` → `bash infra/publish-apps.sh all` (junto com o #38118).

---

## Self-Review (feito contra a spec)

- **Cobertura:** dados/migration (Task 1); `ensurePayLink` com valor editável, elegibilidade e troca de valor cancelando Pix pendente (Tasks 2–3); `payLinkView` sem efeito colateral (Task 2); `payLinkPix` com reaproveitamento, conferência do Pix antigo, limite de 10 e 502 (Task 3); rotas gestão/públicas e ramo do webhook com valor ≥ cobrado, ignorando estorno (Task 4); página pública com botão (não automático), contagem, polling 5 s e estados pago/cancelado/404 (Task 5); app com botão condicional, folha com valor editável, compartilhar e copiar (Task 6); `.env.example`, docs e spec (Task 7). "Pix indisponível" na página: erro do `POST` aparece no `error` da página (503/502).
- **Placeholders:** nenhum; todo passo tem código ou comando.
- **Consistência de tipos:** `EnsureResult`, `PayLinkView`, `PayLinkPixResult`, `markLinkPaid(linkId, mpPaymentId, paidAmount)`, `handleLinkPayment(pay, linkId)`, `LINK_REF`, `payerEmail()` usados com as mesmas assinaturas nas Tasks 2–5; `PixCopy` mantém os chamadores antigos (`orderId` agora opcional); `OrderModel.canPayLink` e `PayLinkSheet(order:)` iguais nas Tasks 6.
- **Review Focus:** itens 1–6 têm teste nas tasks indicadas.
