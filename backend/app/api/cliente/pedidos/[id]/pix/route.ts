import { fail, ok } from '@/lib/api';
import { customerRoute } from '@/lib/customer-api';
import { regeneratePix } from '@/lib/pix';

// "Gerar novo Pix": o código tem validade; vencido, o cliente pede outro.
export const POST = customerRoute(async (_req, customer, { id }) => {
  const orderId = Number(id);
  if (!Number.isInteger(orderId)) return fail('Pedido não encontrado.', 404);
  const r = await regeneratePix(orderId, customer.id);
  return r.ok ? ok(r) : fail(r.error, r.status);
});
