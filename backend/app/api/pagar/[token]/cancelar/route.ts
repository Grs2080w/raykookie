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
