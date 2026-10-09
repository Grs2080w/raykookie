import crypto from 'node:crypto';
import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { q, q1, tx } from './db';
import { DEFAULT_CATEGORIES, CatKind } from './defaults';

const COOKIE = 'rayka_session';
const SESSION_DAYS = 30;

export type User = { id: number; username: string };

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

export async function createSession(userId: number) {
  const token = crypto.randomBytes(32).toString('base64url');
  const expires = new Date(Date.now() + SESSION_DAYS * 864e5);
  await q('INSERT INTO sessions(user_id, token_hash, expires_at) VALUES ($1,$2,$3)', [userId, sha(token), expires]);
  (await cookies()).set(COOKIE, token, {
    httpOnly: true, sameSite: 'lax', path: '/', expires,
    secure: process.env.COOKIE_SECURE === 'true',
  });
}

export async function getUser(): Promise<User | null> {
  const token = (await cookies()).get(COOKIE)?.value;
  if (!token) return null;
  return q1<User>(
    `SELECT u.id, u.username FROM sessions s JOIN users u ON u.id = s.user_id
     WHERE s.token_hash = $1 AND s.expires_at > now()`,
    [sha(token)],
  );
}
export async function requireUser(): Promise<User> {
  const u = await getUser();
  if (!u) redirect('/login');
  return u;
}
export async function destroySession() {
  const jar = await cookies();
  const token = jar.get(COOKIE)?.value;
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
