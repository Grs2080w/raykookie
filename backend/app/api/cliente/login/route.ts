import { fail, ok } from '@/lib/api';
import { loginBlocked, loginFailed, loginOk, wantsToken } from '@/lib/auth';
import { createCustomerSession, loginCustomer } from '@/lib/customer-auth';
import { cleanText } from '@shared/text';

export async function POST(req: Request) {
  const b = await req.json().catch(() => ({}));
  const email = cleanText(b.email).toLowerCase();
  const password = String(b.password ?? '');
  const key = `cliente|${email}|${req.headers.get('x-forwarded-for') ?? 'local'}`;
  if (loginBlocked(key)) return fail('Muitas tentativas. Espere alguns minutos e tente de novo.', 429);
  const r = await loginCustomer(email, password);
  if ('error' in r) {
    loginFailed(key);
    return r.error === 'google-only'
      ? fail('Essa conta foi criada com o Google. Toque em “Continuar com o Google”.', 401)
      : fail('E-mail ou senha incorretos.', 401);
  }
  loginOk(key);
  const token = await createCustomerSession(r.customer.id);
  return ok(await wantsToken() ? { ok: true, token } : { ok: true });
}
