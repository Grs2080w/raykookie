// Suíte AO VIVO contra a API de verdade do Mercado Pago (nada mockado): tokenização pela chave pública,
// bandeira/emissor pelo bin e criação real de pagamento com os nomes mágicos do titular.
// Só roda sob pedido (ver vitest.config.ts):
//   RUN_MP_SANDBOX=1 npx vitest run tests/card-pay.sandbox.test.ts
// com MP_SANDBOX_PUBLIC_KEY / MP_SANDBOX_ACCESS_TOKEN (e, opcional, MP_SANDBOX_BUYER_EMAIL) no ambiente.
//
// Regras desta suíte (Tarefa 0.3, diagnóstico em docs/mercadopago-cartao-diagnostico.md):
// - Antes de tudo confere em GET /users/me que o token é de uma CONTA DE TESTE (`tags` contém `test_user`);
//   se não for, falha — nunca cobra cartão de teste numa conta real.
// - Nenhum try/catch transforma erro em sucesso: um 401 "Unauthorized use of live credentials" é falha,
//   com a explicação do que corrigir nas credenciais.
// - Nunca imprime credenciais, tokens de cartão nem e-mails.
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';

const API = 'https://api.mercadopago.com';
const PUB = process.env.MP_SANDBOX_PUBLIC_KEY;
const TOK = process.env.MP_SANDBOX_ACCESS_TOKEN;
const BUYER_EMAIL = process.env.MP_SANDBOX_BUYER_EMAIL || 'test@testuser.com';
const CPF_DOC = '12345678909'; // CPF pedido pela documentação para APRO/OTHE
const CPF_OUTRO = '52998224725'; // CPF válido qualquer (ver backend/tests/cpf.test.ts)
const NOT_TEST_ACCOUNT = 'MP_SANDBOX_ACCESS_TOKEN não é credencial de teste — gere em Suas integrações → Testes → Credenciais de teste';

type Card = { label: string; number: string; cvv: string; paymentMethodId: string; paymentTypeId: string; issuerId: string };

// Cartões de teste conferidos contra a API em 2026-10-08 (GET /v1/payment_methods/installments?bin=…).
// A Mastercard 5031 4332 1540 6351 da tabela do plano NÃO é reconhecida (404, ver teste abaixo);
// a que funciona é a 5031 7557 3453 0604. A "Elo débito" volta como `prepaid_card`, não débito.
const CARDS: Card[] = [
  { label: 'Mastercard', number: '5031755734530604', cvv: '123', paymentMethodId: 'master', paymentTypeId: 'credit_card', issuerId: '24' },
  { label: 'Visa', number: '4235647728025682', cvv: '123', paymentMethodId: 'visa', paymentTypeId: 'credit_card', issuerId: '25' },
  { label: 'American Express', number: '375365153556885', cvv: '1234', paymentMethodId: 'amex', paymentTypeId: 'credit_card', issuerId: '18' },
  { label: 'Elo', number: '5067766783888311', cvv: '123', paymentMethodId: 'elo', paymentTypeId: 'prepaid_card', issuerId: '687' },
];
const MASTER = CARDS[0];

const d = PUB && TOK ? describe : describe.skip;

/** Tokeniza direto pela chave pública, como o site (SDK) e o app (card_fields.dart) fazem. */
async function tokenize(card: Card, cardholderName: string, cpf = CPF_DOC, securityCode = card.cvv) {
  const res = await fetch(`${API}/v1/card_tokens?public_key=${PUB}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      card_number: card.number, expiration_month: 11, expiration_year: 2030, security_code: securityCode,
      cardholder: { name: cardholderName, identification: { type: 'CPF', number: cpf } },
    }),
  });
  return { status: res.status, body: await res.json() };
}

/** Bandeira, tipo e emissor pelo bin — o mesmo endpoint que CardFields.tsx/card_fields.dart usam. */
async function installments(bin: string) {
  const res = await fetch(`${API}/v1/payment_methods/installments?bin=${bin}&amount=10&public_key=${PUB}`);
  return { status: res.status, body: await res.json() };
}

type PayInput = { token: string; paymentMethodId: string; issuerId?: string; email: string; cpf: string; firstName: string };

/** POST /v1/payments direto na API (para variar `issuer_id` e e-mail sem mexer no código de produção).
 *  Um 401 de "live credentials" vira erro explicando a causa, em vez de ser engolido. */
async function pay(p: PayInput) {
  const body: Record<string, unknown> = {
    transaction_amount: 10, token: p.token, description: 'Raykookie - suíte ao vivo 0.3', installments: 1,
    payment_method_id: p.paymentMethodId,
    payer: { email: p.email, first_name: p.firstName, identification: { type: 'CPF', number: p.cpf } },
    external_reference: 'sandbox-0.3',
  };
  if (p.issuerId !== undefined) body.issuer_id = p.issuerId;
  const res = await fetch(`${API}/v1/payments`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${TOK}`, 'Content-Type': 'application/json', 'X-Idempotency-Key': randomUUID() },
    body: JSON.stringify(body),
  });
  const json = await res.json();
  if (res.status === 401 && /live credentials/i.test(String(json.message))) {
    throw new Error('Mercado Pago recusou o par MP_SANDBOX_* com 401 "Unauthorized use of live credentials": o token é '
      + 'APP_USR de um aplicativo em sandbox_mode/não certificado. Use as credenciais TEST- (Credenciais de teste) '
      + 'ou ative as credenciais de produção do aplicativo do vendedor de teste — ver docs/mercadopago-cartao-diagnostico.md');
  }
  return { http: res.status, status: json.status as string | undefined, detail: json.status_detail as string | undefined, message: json.message as string | undefined };
}

/** Tokeniza a Mastercard com o nome mágico e paga, enviando `issuer_id` (como manda a documentação). */
async function payMagic(name: string, cpf: string) {
  const t = await tokenize(MASTER, name, cpf);
  expect(t.status).toBe(201);
  return pay({ token: t.body.id, paymentMethodId: MASTER.paymentMethodId, issuerId: MASTER.issuerId, email: BUYER_EMAIL, cpf, firstName: name });
}

d('Mercado Pago — suíte ao vivo (API real, credenciais de conta de teste)', () => {
  let sellerEmail = '';

  beforeAll(async () => {
    // Trava de segurança: só segue se o token for de uma conta de teste (campo confirmado na API em 2026-10-08:
    // `tags` traz "test_user" e `test_data.test_user` é true).
    const res = await fetch(`${API}/users/me`, { headers: { Authorization: `Bearer ${TOK}` } });
    expect(res.status).toBe(200);
    const me = await res.json();
    if (!Array.isArray(me.tags) || !me.tags.includes('test_user')) throw new Error(NOT_TEST_ACCOUNT);
    sellerEmail = String(me.email);
  });

  describe('conta', () => {
    it('o token é de uma conta de teste (tags contém test_user e test_data.test_user = true)', async () => {
      const me = await (await fetch(`${API}/users/me`, { headers: { Authorization: `Bearer ${TOK}` } })).json();
      expect(me.tags).toContain('test_user');
      expect(me.test_data?.test_user).toBe(true);
      expect(me.site_id).toBe('MLB');
    });
  });

  describe('bandeira e emissor pelo bin (H5: o payment_method_id que o site/app mandam)', () => {
    it.each(CARDS)('$label → payment_method_id=$paymentMethodId, issuer.id=$issuerId', async (c) => {
      const { status, body } = await installments(c.number.slice(0, 6));
      expect(status).toBe(200);
      expect(body).toHaveLength(1);
      expect(body[0].payment_method_id).toBe(c.paymentMethodId);
      expect(body[0].payment_type_id).toBe(c.paymentTypeId);
      expect(String(body[0].issuer.id)).toBe(c.issuerId);
      expect(body[0].payer_costs.find((x: { installments: number }) => x.installments === 1)?.installment_rate).toBe(0);
    });

    it('a Mastercard 5031 4332 1540 6351 (tabela do plano) não é reconhecida pela API: 404 "payment method not found"', async () => {
      const { status, body } = await installments('503143');
      expect(status).toBe(404);
      expect(body.message).toBe('payment method not found');
    });

    it('GET /v1/payment_methods?bin=… não filtra pelo bin (motivo de o site/app usarem /installments)', async () => {
      const [semBin, comBin] = await Promise.all([
        fetch(`${API}/v1/payment_methods?public_key=${PUB}`).then((r) => r.json()),
        fetch(`${API}/v1/payment_methods?bin=${MASTER.number.slice(0, 6)}&public_key=${PUB}`).then((r) => r.json()),
      ]);
      expect(comBin.length).toBe(semBin.length);
    });
  });

  describe('tokenização (POST /v1/card_tokens pela chave pública)', () => {
    it.each(CARDS)('$label gera token com os 6 primeiros e 4 últimos dígitos certos', async (c) => {
      const { status, body } = await tokenize(c, 'APRO');
      expect(status).toBe(201);
      expect(body.id).toMatch(/^[0-9a-f]{32}$/);
      expect(body.first_six_digits).toBe(c.number.slice(0, 6));
      expect(body.last_four_digits).toBe(c.number.slice(-4));
      expect(body.expiration_month).toBe(11);
      expect(body.expiration_year).toBe(2030);
      expect(body.status).toBe('active');
    });

    it('CVV com tamanho errado é recusado na tokenização, antes de qualquer cobrança', async () => {
      const { status } = await tokenize(MASTER, 'APRO', CPF_DOC, '12345');
      expect(status).toBeGreaterThanOrEqual(400);
      expect(status).toBeLessThan(500);
    });

    it('o servidor consegue consultar o token com o access token (GET /v1/card_tokens/{id})', async () => {
      const t = await tokenize(MASTER, 'APRO');
      const res = await fetch(`${API}/v1/card_tokens/${t.body.id}`, { headers: { Authorization: `Bearer ${TOK}` } });
      expect(res.status).toBe(200);
      const body = await res.json();
      expect(body.first_six_digits).toBe(MASTER.number.slice(0, 6));
      expect(body.public_key).toBe(PUB); // H6: o token carrega a chave pública que o gerou
    });
  });

  // Nomes mágicos do titular (documentação "Realizar compra teste"). APRO e OTHE pedem o CPF 12345678909.
  const MAGIC: { name: string; cpf: string; status: string; detail?: string }[] = [
    { name: 'APRO', cpf: CPF_DOC, status: 'approved' },
    { name: 'OTHE', cpf: CPF_DOC, status: 'rejected', detail: 'cc_rejected_other_reason' },
    { name: 'CONT', cpf: CPF_OUTRO, status: 'in_process' },
    { name: 'CALL', cpf: CPF_OUTRO, status: 'rejected', detail: 'cc_rejected_call_for_authorize' },
    { name: 'FUND', cpf: CPF_OUTRO, status: 'rejected', detail: 'cc_rejected_insufficient_amount' },
    { name: 'SECU', cpf: CPF_OUTRO, status: 'rejected', detail: 'cc_rejected_bad_filled_security_code' },
    { name: 'EXPI', cpf: CPF_OUTRO, status: 'rejected', detail: 'cc_rejected_bad_filled_date' },
    { name: 'FORM', cpf: CPF_OUTRO, status: 'rejected', detail: 'cc_rejected_bad_filled_other' },
  ];

  describe('pagamento com cada nome mágico (POST /v1/payments com issuer_id)', () => {
    it.each(MAGIC.map((m) => ({ ...m, expected: m.detail ?? m.status })))('$name → $expected', async (m) => {
      const r = await payMagic(m.name, m.cpf);
      expect(r.http).toBe(201);
      expect(r.status).toBe(m.status);
      if (m.detail) expect(r.detail).toBe(m.detail);
    });
  });

  describe('H3: issuer_id', () => {
    it.each(CARDS)('$label APRO: aprovado com issuer_id; sem issuer_id o resultado é registrado', async (c) => {
      const com = await tokenize(c, 'APRO');
      const comIssuer = await pay({ token: com.body.id, paymentMethodId: c.paymentMethodId, issuerId: c.issuerId, email: BUYER_EMAIL, cpf: CPF_DOC, firstName: 'APRO' });
      expect(comIssuer.status).toBe('approved');

      const sem = await tokenize(c, 'APRO');
      const semIssuer = await pay({ token: sem.body.id, paymentMethodId: c.paymentMethodId, email: BUYER_EMAIL, cpf: CPF_DOC, firstName: 'APRO' });
      console.info(`[H3] ${c.label} sem issuer_id → http ${semIssuer.http} ${semIssuer.status ?? ''} ${semIssuer.detail ?? semIssuer.message ?? ''}`);
      expect(semIssuer.http).toBeLessThan(500);
    });
  });

  describe('H4: e-mail do pagador', () => {
    it('APRO com o e-mail do comprador de teste é aprovado', async () => {
      const r = await payMagic('APRO', CPF_DOC);
      expect(r.status).toBe('approved');
    });

    it('APRO com o e-mail do próprio vendedor (pagador = recebedor) é recusado com erro 4xx', async () => {
      const t = await tokenize(MASTER, 'APRO');
      const r = await pay({ token: t.body.id, paymentMethodId: 'master', issuerId: MASTER.issuerId, email: sellerEmail, cpf: CPF_DOC, firstName: 'APRO' });
      console.info(`[H4] e-mail do vendedor → http ${r.http} ${r.status ?? ''} ${r.detail ?? r.message ?? ''}`);
      expect(r.http).toBeGreaterThanOrEqual(400);
      expect(r.http).toBeLessThan(500);
    });

    it('APRO com um e-mail qualquer de fora (o que o teste antigo usava) — resultado registrado', async () => {
      const t = await tokenize(MASTER, 'APRO');
      const r = await pay({ token: t.body.id, paymentMethodId: 'master', issuerId: MASTER.issuerId, email: 'teste@raykookie.com.br', cpf: CPF_DOC, firstName: 'APRO' });
      console.info(`[H4] e-mail externo → http ${r.http} ${r.status ?? ''} ${r.detail ?? r.message ?? ''}`);
      expect(r.http).toBeLessThan(500);
    });
  });

  describe('H5: payment_method_id errado', () => {
    it('Visa enviada como "master" não é aprovada', async () => {
      const visa = CARDS[1];
      const t = await tokenize(visa, 'APRO');
      const r = await pay({ token: t.body.id, paymentMethodId: 'master', issuerId: visa.issuerId, email: BUYER_EMAIL, cpf: CPF_DOC, firstName: 'APRO' });
      console.info(`[H5] Visa como master → http ${r.http} ${r.status ?? ''} ${r.detail ?? r.message ?? ''}`);
      expect(r.status).not.toBe('approved');
    });
  });

  describe('createCardPayment (código real do backend Node, que NÃO envia issuer_id)', () => {
    let prevToken: string | undefined;
    beforeAll(() => { prevToken = process.env.MP_ACCESS_TOKEN; process.env.MP_ACCESS_TOKEN = TOK; });
    afterAll(() => { process.env.MP_ACCESS_TOKEN = prevToken; });

    it('APRO Mastercard: aprovado (se falhar aqui e passar no teste "com issuer_id" acima, H3 está confirmada)', async () => {
      const { createCardPayment } = await import('@/lib/mercadopago');
      const t = await tokenize(MASTER, 'APRO');
      expect(t.status).toBe(201);
      const r = await createCardPayment({
        orderId: 999999, code: 1234, total: 10, email: BUYER_EMAIL, name: 'APRO', cpf: CPF_DOC,
        cardToken: t.body.id, paymentMethodId: MASTER.paymentMethodId, installments: 1, attempt: Date.now(),
      });
      expect(r.status).toBe('approved');
    });
  });
});
