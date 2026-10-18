// Acesso ao backend a partir das páginas. Em produção o backend e o frontend são o mesmo processo/porta (BACKEND_URL aponta para ele mesmo);
// em desenvolvimento, para o backend de desenvolvimento. O cookie de sessão do navegador é repassado.
import { cookies } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import type { Customer, User } from '@shared/types';
import { API_BASE } from '@/lib/urls';

const BACKEND = process.env.BACKEND_URL || (process.env.NODE_ENV === 'production' ? API_BASE : 'http://127.0.0.1:10005');

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

async function request(path: string, opts: { auth: boolean; revalidate?: number }) {
  const headers: Record<string, string> = {};
  if (opts.auth) headers.cookie = (await cookies()).toString();
  return fetch(BACKEND + path, { headers, ...(opts.revalidate ? { next: { revalidate: opts.revalidate } } : { cache: 'no-store' as const }) });
}

/** GET autenticado. 401 manda para o login indicado; 404 vira a página "não encontrada". */
export async function apiGet<T>(path: string, loginPath = '/login'): Promise<T> {
  const res = await request(path, { auth: true });
  if (res.status === 401) redirect(loginPath);
  if (res.status === 404) notFound();
  if (!res.ok) throw new ApiError(res.status, `Backend respondeu ${res.status} em ${path}`);
  return res.json();
}
/** GET autenticado que devolve null quando não há sessão. */
async function apiGetOrNull<T>(path: string): Promise<T | null> {
  const res = await request(path, { auth: true });
  if (res.status === 401) return null;
  if (!res.ok) throw new ApiError(res.status, `Backend respondeu ${res.status} em ${path}`);
  return res.json();
}
/** GET público (sem cookie), com cache curto. */
export async function apiPublic<T>(path: string, revalidate = 30): Promise<T | null> {
  try {
    const res = await request(path, { auth: false, revalidate });
    return res.ok ? await res.json() : null;
  } catch { return null; }
}

export const getUser = () => apiGetOrNull<User>('/api/me');
export const requireUser = () => apiGet<User>('/api/me');
export const getCustomer = () => apiGetOrNull<Customer>('/api/cliente/me');
/** Exige cliente logado; senão leva ao login e volta para `next` depois. */
export async function requireCustomer(next = '/cliente/pedidos'): Promise<Customer> {
  const c = await getCustomer();
  if (!c) redirect(`/cliente/entrar?next=${encodeURIComponent(next)}`);
  return c;
}
