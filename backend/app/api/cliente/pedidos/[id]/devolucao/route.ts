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
