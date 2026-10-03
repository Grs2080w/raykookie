import { fail, ok } from '@/lib/api';
import { payLinkView } from '@/lib/pay-link';

// Página pública do link de pagamento (sem sessão). Só leitura: abrir o link, inclusive a prévia do WhatsApp, nunca gera Pix.
export async function GET(_req: Request, ctx: { params: Promise<{ token: string }> }) {
  const { token } = await ctx.params;
  try {
    const v = await payLinkView(token);
    return v ? ok(v) : fail('Link não encontrado.', 404);
  } catch (e) {
    console.error(e);
    return fail('Algo deu errado. Tente de novo em instantes.', 500);
  }
}
