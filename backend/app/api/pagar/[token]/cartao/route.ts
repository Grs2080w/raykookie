import { fail, ok } from '@/lib/api';
import { payLinkCard } from '@/lib/pay-link';
import { isValidCpf } from '@shared/cpf';
import { MAX_INSTALLMENTS } from '@shared/shop';

// "Pagar com cartão" da página pública do link de pagamento.
export async function POST(req: Request, ctx: { params: Promise<{ token: string }> }) {
  const { token } = await ctx.params;
  const b = await req.json().catch(() => null);
  const cardToken = String(b?.card_token ?? '').trim();
  const paymentMethodId = String(b?.card_payment_method_id ?? '').trim();
  const cpf = String(b?.cpf ?? '').trim();
  const installments = Number(b?.installments);
  if (!cardToken || !paymentMethodId) return fail('Preencha os dados do cartão.');
  if (!isValidCpf(cpf)) return fail('Informe um CPF válido.');
  if (!Number.isInteger(installments) || installments < 1 || installments > MAX_INSTALLMENTS) return fail('Escolha uma quantidade de parcelas válida.');
  try {
    const r = await payLinkCard(token, cardToken, paymentMethodId, installments, cpf);
    return r.ok ? ok(r.approved ? { approved: true } : { approved: false, pending: r.pending, reason: r.pending ? undefined : r.reason }) : fail(r.error, r.status);
  } catch (e) {
    console.error(e);
    return fail('Algo deu errado. Tente de novo em instantes.', 500);
  }
}
