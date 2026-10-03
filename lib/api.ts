import { NextResponse } from 'next/server';
import { getUser, User } from './auth';
import { cleanText, isoDate } from './text';

export const ok = (data: unknown = { ok: true }, status = 200) => NextResponse.json(data, { status });
export const fail = (message: string, status = 400) => NextResponse.json({ error: message }, { status });

type Ctx = { params: Promise<Record<string, string>> };
type Handler = (req: Request, user: User, params: Record<string, string>) => Promise<Response>;

// Exige sessão e converte erros em JSON legível.
export function route(h: Handler) {
  return async (req: Request, ctx: Ctx) => {
    const user = await getUser();
    if (!user) return fail('Sessão expirada. Entre novamente.', 401);
    try {
      return await h(req, user, (await ctx?.params) ?? {});
    } catch (e: any) {
      if (e instanceof InputError) return fail(e.message);
      if (e?.code === '22003') return fail('Algum número está grande demais.', 400);
      if (e?.code === '23505') return fail('Já existe um registro com esse nome.', 409);
      if (e?.code === '23503') return fail('Este item está em uso e não pode ser removido.', 409);
      if (e?.code === '23514') return fail('Algum valor está fora do permitido.', 400);
      console.error(e);
      return fail('Algo deu errado no servidor. Tente de novo.', 500);
    }
  };
}

export class InputError extends Error {}

// Aceita "12,5", "12.5" e "1.500,50". Vazio vira `d`; texto ou negativo é erro (antes virava 0 sem avisar).
export const num = (v: unknown, d = 0) => {
  const s = String(v ?? '').trim();
  if (s === '') return d;
  const n = Number(s.includes(',') ? s.replace(/\./g, '').replace(',', '.') : s);
  if (!Number.isFinite(n)) throw new InputError(`“${s.slice(0, 20)}” não é um número válido.`);
  if (n < 0) throw new InputError('Os valores não podem ser negativos.');
  return n;
};
// Texto livre: sem caracteres de controle (um byte nulo, por exemplo, derrubava o insert no Postgres).
export const str = cleanText;

export { isoDate };
