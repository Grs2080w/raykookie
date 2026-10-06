import { getCustomer } from '@/lib/customer-auth';
import { getFeedbackPhotoForCustomer } from '@/lib/feedback';

// Mesma foto, vista pelo próprio cliente que enviou (ex.: conferir o que mandou).
export async function GET(_req: Request, ctx: { params: Promise<{ id: string; n: string }> }) {
  const { id, n } = await ctx.params;
  const c = await getCustomer();
  if (!c) return new Response(null, { status: 401 });
  const p = await getFeedbackPhotoForCustomer(Number(id), Number(n), c.id);
  if (!p) return new Response(null, { status: 404 });
  return new Response(new Uint8Array(p.data), { headers: { 'Content-Type': p.content_type, 'Cache-Control': 'private, max-age=3600', 'X-Content-Type-Options': 'nosniff' } });
}
