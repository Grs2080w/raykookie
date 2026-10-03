import { fail, ok } from '@/lib/api';
import { createSession, loginBlocked, loginFailed, loginOk, verifyPassword, wantsToken } from '@/lib/auth';
import { q1 } from '@/lib/db';

export async function POST(req: Request) {
  const b = await req.json().catch(() => ({}));
  const username = String(b.username ?? '').trim();
  const password = String(b.password ?? '');
  const key = `${username.toLowerCase()}|${req.headers.get('x-forwarded-for') ?? 'local'}`;
  if (loginBlocked(key)) return fail('Muitas tentativas. Espere alguns minutos e tente de novo.', 429);
  const user = await q1<{ id: number; password_hash: string }>('SELECT id, password_hash FROM users WHERE lower(username) = lower($1)', [username]);
  // verifica mesmo sem usuário, para não revelar se o nome existe pelo tempo de resposta
  const valid = verifyPassword(password, user?.password_hash ?? 'scrypt$00$00') && !!user;
  if (!valid) { loginFailed(key); return fail('Usuário ou senha incorretos.', 401); }
  loginOk(key);
  const token = await createSession(user!.id);
  return ok(await wantsToken() ? { ok: true, token } : { ok: true });
}
