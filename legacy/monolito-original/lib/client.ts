'use client';
// fetch JSON com tratamento de erro padronizado
export async function api<T = any>(url: string, method: string, body?: unknown): Promise<T> {
  let res: Response;
  try { res = await fetch(url, { method, headers: { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) }); }
  catch { throw new Error('Sem conexão com o servidor. Confira a internet e tente de novo.'); }
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && !url.startsWith('/api/auth')) { location.href = '/login'; throw new Error(data.error ?? 'Sessão expirada.'); }
  if (!res.ok) throw new Error(data.error ?? 'Não foi possível concluir. Tente de novo.');
  return data as T;
}
