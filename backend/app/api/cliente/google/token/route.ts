import { fail, ok } from '@/lib/api';
import { wantsToken } from '@/lib/auth';
import { createCustomerSession, upsertGoogleCustomer } from '@/lib/customer-auth';
import { verifyGoogleIdToken } from '@/lib/google';

// Login com Google pelo app: recebe { idToken } obtido no aparelho e devolve a sessão.
// Usa o mesmo GOOGLE_CLIENT_ID do login pelo site (o app o passa como serverClientId).
export async function POST(req: Request) {
  const clientId = process.env.GOOGLE_CLIENT_ID;
  if (!clientId) return fail('O login com o Google ainda não está disponível. Entre com e-mail e senha.', 503);
  const b = await req.json().catch(() => ({}));
  const idToken = String(b.idToken ?? '');
  if (idToken.length < 20 || idToken.length > 4000) return fail('Identificação do Google ausente ou inválida.');
  try {
    const r = await verifyGoogleIdToken(idToken, clientId);
    if (r.error) return fail(r.error, 401);
    const customer = await upsertGoogleCustomer(r.claims!);
    const token = await createCustomerSession(customer.id);
    return ok(await wantsToken() ? { ok: true, token } : { ok: true });
  } catch (e) {
    console.error('google token', e);
    return fail('Não foi possível confirmar com o Google agora. Tente de novo em instantes.', 502);
  }
}
