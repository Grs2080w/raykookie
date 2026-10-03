import fs from 'node:fs';
import { Readable } from 'node:stream';
import { fail } from '@/lib/api';
import { getUser } from '@/lib/auth';
import { isAppId, readManifest, releaseFilePath, verifyDownload } from '@/lib/downloads';

// Baixa o APK. `client` é público; `server` (gestão) exige sessão da confeiteira (cookie ou Bearer) ou um link assinado
// de curta duração (/api/downloads/server/link). Aceita Range, para retomar downloads interrompidos.
export async function GET(req: Request, ctx: { params: Promise<{ app: string }> }) {
  const { app } = await ctx.params;
  if (!isAppId(app)) return fail('App não encontrado.', 404);
  if (app === 'server') {
    const t = new URL(req.url).searchParams.get('t');
    if (!(await getUser()) && !verifyDownload('server', t)) return fail('Entre na sua conta para baixar o app de gestão.', 401);
  }
  const r = readManifest()[app];
  if (!r) return fail('Esse app ainda não foi publicado.', 404);
  const file = releaseFilePath(r);
  const size = fs.statSync(file).size;

  const headers: Record<string, string> = {
    'content-type': 'application/vnd.android.package-archive',
    'content-disposition': `attachment; filename="${r.file}"`,
    'accept-ranges': 'bytes',
    'cache-control': 'no-store',
    etag: `"${r.sha256}"`,
    'x-app-version': `${r.version}+${r.build}`,
  };
  const range = /^bytes=(\d*)-(\d*)$/.exec(req.headers.get('range') ?? '');
  let start = 0, end = size - 1, status = 200;
  if (range && (range[1] || range[2])) {
    if (range[1]) { start = Number(range[1]); if (range[2]) end = Math.min(Number(range[2]), size - 1); }
    else { start = Math.max(0, size - Number(range[2])); } // bytes=-N: os últimos N bytes
    if (start > end || start >= size) return new Response(null, { status: 416, headers: { 'content-range': `bytes */${size}` } });
    status = 206;
    headers['content-range'] = `bytes ${start}-${end}/${size}`;
  }
  headers['content-length'] = String(end - start + 1);
  const body = Readable.toWeb(fs.createReadStream(file, { start, end })) as unknown as ReadableStream;
  return new Response(body, { status, headers });
}
