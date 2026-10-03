import { q1 } from '@/lib/db';
import { getCustomer } from '@/lib/customer-auth';

// Foto enviada pelo próprio cliente na solicitação de devolução: só ele a vê (e a confeiteira, pelo painel).
export async function GET(_req: Request, ctx: { params: Promise<{ rid: string; n: string }> }) {
  const { rid, n } = await ctx.params;
  const c = await getCustomer();
  if (!c) return new Response(null, { status: 401 });
  const p = await q1<{ content_type: string; data: Buffer }>(
    `SELECT p.content_type, p.data FROM refund_photos p JOIN refunds r ON r.id = p.refund_id WHERE r.id = $1 AND r.customer_id = $2 AND p.idx = $3`, [Number(rid), c.id, Number(n)]);
  if (!p) return new Response(null, { status: 404 });
  return new Response(new Uint8Array(p.data), { headers: { 'Content-Type': p.content_type, 'Cache-Control': 'private, max-age=3600', 'X-Content-Type-Options': 'nosniff' } });
}
