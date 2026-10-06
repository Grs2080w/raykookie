// Testes contra a API de VERDADE do Mercado Pago (não mockada) — tokenização real, consulta real de
// parcelas/bandeira pelo bin e criação real de pagamento. Só roda com credenciais de um "usuário de teste"
// do Mercado Pago (gerado em Suas integrações → sua aplicação → Contas de teste); sem isso, é ignorado.
// Nunca usa a conta de produção: card_tokens/payments aqui são sempre contra MP_SANDBOX_*, nunca MP_ACCESS_TOKEN.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

const PUB = process.env.MP_SANDBOX_PUBLIC_KEY;
const TOK = process.env.MP_SANDBOX_ACCESS_TOKEN;
const CARD = process.env.MP_SANDBOX_CARD_NUMBER; // ex.: 5480832801033311 (Mastercard de teste do Mercado Pago)
const CVC = process.env.MP_SANDBOX_CARD_CVC ?? '123';
const EXP_MONTH = process.env.MP_SANDBOX_CARD_EXP_MONTH ?? '11';
const EXP_YEAR = process.env.MP_SANDBOX_CARD_EXP_YEAR ?? '2030';
const CPF = '52998224725'; // CPF válido de teste (ver backend/tests/cpf.test.ts)

const d = PUB && TOK && CARD ? describe : describe.skip;

/** Tokeniza contra a API pública de verdade — o mesmo que o navegador/app fazem, nunca pelo nosso backend. */
async function tokenizeReal(cardholderName: string) {
  const res = await fetch(`https://api.mercadopago.com/v1/card_tokens?public_key=${PUB}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      card_number: CARD, expiration_month: EXP_MONTH, expiration_year: EXP_YEAR, security_code: CVC,
      cardholder: { name: cardholderName, identification: { type: 'CPF', number: CPF } },
    }),
  });
  const body = await res.json();
  return { status: res.status, body };
}

d('Mercado Pago sandbox (API real, sem mocks)', () => {
  let prevToken: string | undefined;
  beforeEach(() => { prevToken = process.env.MP_ACCESS_TOKEN; process.env.MP_ACCESS_TOKEN = TOK; });
  afterEach(() => { process.env.MP_ACCESS_TOKEN = prevToken; });

  describe('tokenização do cartão (POST /v1/card_tokens, direto pela chave pública)', () => {
    it('cartão válido gera um token com os 6 primeiros e os 4 últimos dígitos certos', async () => {
      const { status, body } = await tokenizeReal('APRO');
      expect(status).toBe(201);
      expect(body.id).toBeTruthy();
      expect(body.first_six_digits).toBe(CARD!.slice(0, 6));
      expect(body.last_four_digits).toBe(CARD!.slice(-4));
      expect(body.expiration_month).toBe(Number(EXP_MONTH));
      expect(body.expiration_year).toBe(Number(EXP_YEAR));
    });
    it('CVV errado (1 dígito a mais do que o cartão aceita) é recusado na tokenização, antes de qualquer cobrança', async () => {
      const res = await fetch(`https://api.mercadopago.com/v1/card_tokens?public_key=${PUB}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ card_number: CARD, expiration_month: EXP_MONTH, expiration_year: EXP_YEAR, security_code: '12345', cardholder: { name: 'APRO', identification: { type: 'CPF', number: CPF } } }),
      });
      expect(res.status).toBeGreaterThanOrEqual(400);
    });
  });

  describe('bandeira e parcelas pelo bin (GET /v1/payment_methods/installments) — confirma o formato real usado em CardFields.tsx/card_fields.dart', () => {
    it('devolve payment_method_id e payer_costs crescentes por parcela, 1x sempre sem juros', async () => {
      const bin = CARD!.slice(0, 6);
      const res = await fetch(`https://api.mercadopago.com/v1/payment_methods/installments?bin=${bin}&amount=200&public_key=${PUB}`);
      const list = await res.json();
      expect(Array.isArray(list)).toBe(true);
      expect(list[0].payment_method_id).toBeTruthy();
      const costs = list[0].payer_costs as { installments: number; installment_rate: number }[];
      expect(costs.find((c) => c.installments === 1)?.installment_rate).toBe(0);
    });
    it('GET /v1/payment_methods?bin=... NÃO filtra de verdade pelo bin (motivo de não usarmos mais esse endpoint pra achar a bandeira)', async () => {
      const bin = CARD!.slice(0, 6);
      const [semBin, comBin] = await Promise.all([
        fetch(`https://api.mercadopago.com/v1/payment_methods?public_key=${PUB}`).then((r) => r.json()),
        fetch(`https://api.mercadopago.com/v1/payment_methods?bin=${bin}&public_key=${PUB}`).then((r) => r.json()),
      ]);
      expect(comBin.length).toBe(semBin.length); // mesma quantidade de métodos, só reordenados — não é um filtro de verdade
    });
  });

  describe('createCardPayment (código real do backend, só a rede que é real)', () => {
    it('token + cartão de teste do Mercado Pago: aprova OU recusa por "credenciais de produção com cartão de teste" — nunca outra coisa', async () => {
      const { createCardPayment } = await import('@/lib/mercadopago');
      const { status: tStatus, body: tBody } = await tokenizeReal('APRO');
      expect(tStatus).toBe(201);
      try {
        const pay = await createCardPayment({
          orderId: 999999, code: 1234, total: 10, email: 'teste@raykookie.com.br', name: 'Maria Teste', cpf: CPF,
          cardToken: tBody.id, paymentMethodId: 'master', installments: 1, attempt: 1,
        });
        // credenciais de verdade de "usuário de teste": aprova de verdade
        expect(pay.status).toBe('approved');
      } catch (e: any) {
        // MP_SANDBOX_* ainda não é um usuário de teste de verdade (é o que aconteceu com as credenciais
        // testadas nesta sessão): bloqueia com esse erro específico, documentado aqui para não confundir
        // com um bug do nosso código caso alguém rode de novo com as mesmas credenciais por engano.
        expect(String(e.message)).toMatch(/Unauthorized use of live credentials|401/);
      }
    });
  });
});
