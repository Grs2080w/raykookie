import { fail, ok } from '@/lib/api';
import { cancelShopOrder } from '@/lib/cancel';
import { customerRoute } from '@/lib/customer-api';
import { CANCEL_REASONS } from '@shared/refund';
import { cleanText } from '@shared/text';

// O cliente cancela o próprio pedido enquanto a produção não começou. Se já pagou, o Pix volta na hora.
export const POST = customerRoute(async (req, customer, { id }) => {
  const orderId = Number(id);
  if (!Number.isInteger(orderId)) return fail('Pedido não encontrado.', 404);
  const b = await req.json().catch(() => ({}));
  const code = CANCEL_REASONS.some((r) => r.id === b?.reason_code) ? String(b.reason_code) : 'outro';
  const note = cleanText(b?.reason).slice(0, 150);
  if (code === 'outro' && b?.reason_code && note.length < 3) return fail('Conte o motivo do cancelamento.');
  const r = await cancelShopOrder(orderId, customer.id, note, code);
  return r.ok ? ok(r) : fail(r.error, r.status);
});
