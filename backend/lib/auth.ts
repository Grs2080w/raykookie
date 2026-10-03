import crypto from 'node:crypto';
import { cookies, headers } from 'next/headers';
import { q, q1, tx } from './db';
import { DEFAULT_CATEGORIES, CatKind } from './defaults';

const COOKIE = 'rayka_session';
const SESSION_DAYS = 30;

export type { User } from '@shared/types';
import type { User } from '@shared/types';

export function hashPassword(password: string): string {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(password, salt, 64);
  return `scrypt$${salt.toString('hex')}$${hash.toString('hex')}`;
}
export function verifyPassword(password: string, stored: string): boolean {
  const [scheme, saltHex, hashHex] = stored.split('$');
  if (scheme !== 'scrypt' || !saltHex || !hashHex) return false;
  const expected = Buffer.from(hashHex, 'hex');
  const actual = crypto.scryptSync(password, Buffer.from(saltHex, 'hex'), expected.length);
  return crypto.timingSafeEqual(expected, actual);
}
export const sha = (s: string) => crypto.createHash('sha256').update(s).digest('hex');

// A sessão vale por cookie (navegador) ou por `Authorization: Bearer <token>` (app mobile).
export async function sessionToken(jar?: Awaited<ReturnType<typeof cookies>>): Promise<string | undefined> {
  const bearer = (await headers()).get('authorization')?.match(/^Bearer\s+(\S+)$/i)?.[1];
  return bearer ?? (jar ?? (await cookies())).get(COOKIE)?.value;
}
/** O app mobile pede o token no corpo da resposta (cabeçalho `x-client: mobile`); o navegador recebe só o cookie httpOnly. */
export async function wantsToken(): Promise<boolean> { return (await headers()).get('x-client') === 'mobile'; }

export async function createSession(userId: number): Promise<string> {
  const token = crypto.randomBytes(32).toString('base64url');
  const expires = new Date(Date.now() + SESSION_DAYS * 864e5);
  await q('INSERT INTO sessions(user_id, token_hash, expires_at) VALUES ($1,$2,$3)', [userId, sha(token), expires]);
  (await cookies()).set(COOKIE, token, {
    httpOnly: true, sameSite: 'lax', path: '/', expires,
    secure: process.env.COOKIE_SECURE === 'true',
  });
  return token;
}

export async function getUser(): Promise<User | null> {
  const token = await sessionToken();
  if (!token) return null;
  return q1<User>(
    `SELECT u.id, u.username FROM sessions s JOIN users u ON u.id = s.user_id
     WHERE s.token_hash = $1 AND s.expires_at > now()`,
    [sha(token)],
  );
}
export async function destroySession() {
  const jar = await cookies();
  const token = await sessionToken(jar);
  if (token) await q('DELETE FROM sessions WHERE token_hash = $1', [sha(token)]);
  jar.delete(COOKIE);
}

export async function registerUser(username: string, password: string): Promise<User> {
  return tx(async (c) => {
    const { rows } = await c.query(
      'INSERT INTO users(username, password_hash) VALUES ($1,$2) RETURNING id, username',
      [username, hashPassword(password)],
    );
    const user = rows[0] as User;
    await c.query('INSERT INTO settings(user_id) VALUES ($1)', [user.id]);
    for (const kind of Object.keys(DEFAULT_CATEGORIES) as CatKind[]) {
      for (const [name, icon, color] of DEFAULT_CATEGORIES[kind]) {
        await c.query('INSERT INTO categories(user_id, kind, name, icon, color) VALUES ($1,$2,$3,$4,$5)', [user.id, kind, name, icon, color]);
      }
    }
    return user;
  });
}

// Limitador simples de tentativas de login (em memória, por usuário+IP)
const attempts = new Map<string, { n: number; until: number }>();
export function loginBlocked(key: string): boolean {
  const a = attempts.get(key);
  return !!a && a.n >= 8 && a.until > Date.now();
}
export function loginFailed(key: string) {
  const a = attempts.get(key);
  attempts.set(key, { n: (a && a.until > Date.now() ? a.n : 0) + 1, until: Date.now() + 10 * 60_000 });
}
export function loginOk(key: string) { attempts.delete(key); }
