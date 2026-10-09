import { fail, ok } from '@/lib/api';
import { createSession, registerUser } from '@/lib/auth';

export async function POST(req: Request) {
  const b = await req.json().catch(() => ({}));
  const username = String(b.username ?? '').trim();
  const password = String(b.password ?? '');
  if (!/^[\p{L}\p{N}_.-]{3,32}$/u.test(username)) return fail('Use de 3 a 32 caracteres: letras, números, ponto, hífen ou sublinhado.');
  if (password.length < 8) return fail('A senha precisa ter pelo menos 8 caracteres.');
  try {
    const user = await registerUser(username, password);
    await createSession(user.id);
    return ok({ ok: true });
  } catch (e: any) {
    if (e?.code === '23505') return fail('Esse usuário já existe. Escolha outro nome.', 409);
    console.error(e);
    return fail('Não foi possível criar a conta agora.', 500);
  }
}
