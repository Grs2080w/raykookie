import { ok } from '@/lib/api';
import { getUser } from '@/lib/auth';
import { readManifest } from '@/lib/downloads';

// Versões publicadas dos apps. O app do cliente é público; o de gestão só aparece para a confeiteira logada.
export async function GET() {
  const m = readManifest();
  const owner = !!(await getUser());
  const out: Record<string, unknown> = {};
  if (m.client) out.client = { ...m.client, url: '/api/downloads/client' };
  if (m.server && owner) out.server = { ...m.server, url: '/api/downloads/server' };
  const res = ok(out);
  res.headers.set('cache-control', 'no-store'); // a versão muda quando o app é republicado
  return res;
}
