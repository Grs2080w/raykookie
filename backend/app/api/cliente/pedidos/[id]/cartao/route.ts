import { fail, ok } from '@/lib/api';
import { customerRoute } from '@/lib/customer-api';
import { retryCard } from '@/lib/card-pay';
import { isValidCpf } from '@shared/cpf';
import { MAX_INSTALLMENTS } from '@shared/shop';

// "Tentar outro cartão": o pedido já existe, sem pagar (criado assim ou com um cartão recusado antes).
export const POST = customerRoute(async (req, customer, { id }) => {
  const orderId = Number(id);
  if (!Number.isInteger(orderId)) return fail('Pedido não encontrado.', 404);
  const b = await req.json().catch(() => null);
  const cardToken = String(b?.card_token ?? '').trim();
  const paymentMethodId = String(b?.card_payment_method_id ?? '').trim();
  const cpf = String(b?.cpf ?? '').trim();
  const installments = Number(b?.installments);
  if (!cardToken || !paymentMethodId) return fail('Preencha os dados do cartão.');
  if (!isValidCpf(cpf)) return fail('Informe um CPF válido.');
  if (!Number.isInteger(installments) || installments < 1 || installments > MAX_INSTALLMENTS) return fail('Escolha uma quantidade de parcelas válida.');

  const r = await retryCard(orderId, customer.id, { cardToken, paymentMethodId, installments, cpf });
  if (!r.ok) return fail(r.error, r.status);
  return ok(r.approved ? { approved: true } : { approved: false, pending: r.pending, reason: r.pending ? undefined : r.reason });
});
