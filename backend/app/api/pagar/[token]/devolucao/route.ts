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
