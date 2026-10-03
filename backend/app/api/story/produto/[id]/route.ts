import { q1 } from '@/lib/db';
import { getMenu } from '@/lib/shop-menu';
import { produtoStory } from '@/lib/story';

// Story de um produto do cardápio (PNG 1080×1920): foto, nome, preço e gramatura. Só produtos que estão no cardápio.
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }) {
  const { id } = await ctx.params;
  const item = (await getMenu()).items.find((i) => i.id === id);
  if (!item) return Response.json({ error: 'Produto não encontrado no cardápio.' }, { status: 404 });
  const photo = await q1<{ content_type: string; data: Buffer }>('SELECT content_type, data FROM recipe_photos WHERE recipe_id = $1', [Number(id)]);
  const res = produtoStory(item, photo ? { type: photo.content_type, data: photo.data } : null);
  res.headers.set('Content-Disposition', `inline; filename="story-${id}.png"`);
  res.headers.set('Cache-Control', 'public, max-age=300');
  return res;
}
