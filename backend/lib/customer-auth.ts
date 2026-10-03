import crypto from 'node:crypto';
import { cookies, headers } from 'next/headers';
import { hashPassword, sha, verifyPassword } from './auth';
import { q, q1 } from './db';

const COOKIE = 'rk_customer';
const SESSION_DAYS = 30;

export type { Customer } from '@shared/types';
import type { Customer } from '@shared/types';
const COLS = 'id, email, name, phone, cep, street, number, complement, district, city';

async function customerToken(jar?: Awaited<ReturnType<typeof cookies>>): Promise<string | undefined> {
  const bearer = (await headers()).get('authorization')?.match(/^Bearer\s+(\S+)$/i)?.[1];
  return bearer ?? (jar ?? (await cookies())).get(COOKIE)?.value;
}

export async function createCustomerSession(customerId: number): Promise<string> {
  const token = crypto.randomBytes(32).toString('base64url');
  const expires = new Date(Date.now() + SESSION_DAYS * 864e5);
  await q('INSERT INTO customer_sessions(token_hash, customer_id, expires_at) VALUES ($1,$2,$3)', [sha(token), customerId, expires]);
  (await cookies()).set(COOKIE, token, { httpOnly: true, sameSite: 'lax', path: '/', expires, secure: process.env.COOKIE_SECURE === 'true' });
  return token;
}

export async function getCustomer(): Promise<Customer | null> {
  const token = await customerToken();
  if (!token) return null;
  return q1<Customer>(
    `SELECT ${COLS.split(', ').map((c) => `c.${c}`).join(', ')} FROM customer_sessions s JOIN customers c ON c.id = s.customer_id
     WHERE s.token_hash = $1 AND s.expires_at > now()`,
    [sha(token)],
  );
}

export async function destroyCustomerSession() {
  const jar = await cookies();
  const token = await customerToken(jar);
  if (token) await q('DELETE FROM customer_sessions WHERE token_hash = $1', [sha(token)]);
  jar.delete(COOKIE);
}

export async function registerCustomer(name: string, email: string, password: string): Promise<Customer> {
  const c = await q1<Customer>(`INSERT INTO customers(email, name, password_hash) VALUES ($1,$2,$3) RETURNING ${COLS}`, [email, name, hashPassword(password)]);
  return c!;
}

export type LoginResult = { customer: Customer } | { error: 'invalid' | 'google-only' };
export async function loginCustomer(email: string, password: string): Promise<LoginResult> {
  const row = await q1<Customer & { password_hash: string | null }>(`SELECT ${COLS}, password_hash FROM customers WHERE lower(email) = lower($1) AND NOT guest`, [email]);
  // verifica mesmo sem conta, para o tempo de resposta não revelar se o e-mail existe
  const valid = verifyPassword(password, row?.password_hash ?? 'scrypt$00$00');
  if (row && !row.password_hash) return { error: 'google-only' };
  if (!row || !valid) return { error: 'invalid' };
  const { password_hash: _omit, ...customer } = row;
  return { customer };
}

/** Entrada pelo Google: acha pela conta Google, senão pelo e-mail (e vincula), senão cria. */
export async function upsertGoogleCustomer(p: { sub: string; email: string; name: string }): Promise<Customer> {
  const bySub = await q1<Customer>(`SELECT ${COLS} FROM customers WHERE google_sub = $1`, [p.sub]);
  if (bySub) return bySub;
  const byEmail = await q1<Customer>(`UPDATE customers SET google_sub = $2 WHERE lower(email) = lower($1) AND NOT guest RETURNING ${COLS}`, [p.email, p.sub]);
  if (byEmail) return byEmail;
  return (await q1<Customer>(`INSERT INTO customers(email, name, google_sub) VALUES ($1,$2,$3) RETURNING ${COLS}`, [p.email, p.name || p.email.split('@')[0], p.sub]))!;
}
