import { fail, ok } from '@/lib/api';
import { createCustomerSession, registerCustomer } from '@/lib/customer-auth';
import { isEmail } from '@/lib/shop';
import { cleanText } from '@/lib/text';
import { loginBlocked, loginFailed } from '@/lib/auth';

export async function POST(req: Request) {
  const b = await req.json().catch(() => ({}));
  const name = cleanText(b.name).slice(0, 80);
  const email = cleanText(b.email).toLowerCase();
  const password = String(b.password ?? '');
  const key = `cadastro|${req.headers.get('x-forwarded-for') ?? 'local'}`;
  if (loginBlocked(key)) return fail('Muitas tentativas. Espere alguns minutos e tente de novo.', 429);
  if (name.length < 2) return fail('Informe seu nome.');
  if (!isEmail(email)) return fail('Esse e-mail não parece certo. Confira e tente de novo.');
  if (password.length < 8) return fail('A senha precisa ter pelo menos 8 caracteres.');
  if (password.length > 200) return fail('A senha é longa demais.');
  loginFailed(key); // conta cada cadastro para frear criação em massa
  try {
    const c = await registerCustomer(name, email, password);
    await createCustomerSession(c.id);
    return ok({ ok: true }, 201);
  } catch (e: any) {
    if (e?.code === '23505') return fail('Já existe uma conta com esse e-mail. Entre com ela ou use o Google.', 409);
    console.error(e);
    return fail('Não conseguimos criar sua conta agora. Tente de novo.', 500);
  }
}
