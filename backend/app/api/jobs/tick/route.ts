import { timingSafeEqual } from 'node:crypto';
import { retryDue, reconcilePending } from '@/lib/refunds';
import { reconcileLinks } from '@/lib/pay-link';
import { cleanupOldRefundPhotos } from '@/lib/jobs';
import { refreshLinkedPrices } from '@/lib/suppliers';

// Chamado a cada poucos minutos pelo próprio servidor (server.mjs), só por ele: exige o segredo JOBS_SECRET.
export async function POST(req: Request) {
  const secret = process.env.JOBS_SECRET ?? '';
  const got = req.headers.get('x-jobs-secret') ?? '';
  const a = Buffer.from(secret), b = Buffer.from(got);
  if (!secret || a.length !== b.length || !timingSafeEqual(a, b)) return Response.json({ error: 'Não autorizado' }, { status: 401 });
  const out: Record<string, unknown> = {};
  try { out.retried = await retryDue(); } catch (e) { console.error('[jobs] retryDue', e); }
  const h = new Date().getUTCHours(), m = new Date().getUTCMinutes();
  if (m < 5 && h % 6 === 0) { // a cada 6 h; cada conciliação isolada para uma falha não pular a outra
    let rec = 0, ok = false;
    try { rec += await reconcilePending(); ok = true; } catch (e) { console.error('[jobs] reconcile', e); }
    try { rec += await reconcileLinks(); ok = true; } catch (e) { console.error('[jobs] reconcile links', e); }
    if (ok) out.reconciled = rec;
  }
  if (m < 5 && h === 7) { try { out.prices = await refreshLinkedPrices(); } catch (e) { console.error('[jobs] preços', e); } } // 1 vez por dia (4h em Brasília)
  if (m < 5 && h === 6) { try { out.photos = await cleanupOldRefundPhotos(); } catch (e) { console.error('[jobs] limpeza', e); } } // 1 vez por dia
  return Response.json({ ok: true, ...out });
}
