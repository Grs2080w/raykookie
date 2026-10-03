import { fail, ok } from '@/lib/api';
import { getUser } from '@/lib/auth';
import { isAppId, signDownload } from '@/lib/downloads';

// Gera um link de download com validade de 5 minutos (usado pelo app, que abre o navegador do aparelho).
export async function POST(_req: Request, ctx: { params: Promise<{ app: string }> }) {
  const { app } = await ctx.params;
  if (!isAppId(app)) return fail('App não encontrado.', 404);
  if (app === 'client') return ok({ url: '/api/downloads/client' });
  if (!(await getUser())) return fail('Entre na sua conta para baixar o app de gestão.', 401);
  return ok({ url: `/api/downloads/server?t=${encodeURIComponent(signDownload('server'))}` });
}
