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
