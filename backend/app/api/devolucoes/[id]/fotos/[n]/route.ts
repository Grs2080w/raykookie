import { q1 } from '@/lib/db';
import { getUser } from '@/lib/auth';

export async function GET(_req: Request, ctx: { params: Promise<{ id: string; n: string }> }) {
  const { id, n } = await ctx.params;
  const u = await getUser();
  if (!u) return new Response(null, { status: 401 });
  const p = await q1<{ content_type: string; data: Buffer }>(
    `SELECT p.content_type, p.data FROM refund_photos p JOIN refunds r ON r.id = p.refund_id WHERE r.id = $1 AND r.owner_id = $2 AND p.idx = $3`, [Number(id), u.id, Number(n)]);
  if (!p) return new Response(null, { status: 404 });
  return new Response(new Uint8Array(p.data), { headers: { 'Content-Type': p.content_type, 'Cache-Control': 'private, max-age=3600', 'X-Content-Type-Options': 'nosniff' } });
}
