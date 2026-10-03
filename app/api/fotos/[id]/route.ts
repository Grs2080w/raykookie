import { getUser } from '@/lib/auth';
import { q1 } from '@/lib/db';
import { shopOwnerId } from '@/lib/shop-queries';

// Foto pública de uma receita do cardápio. Só entrega fotos de receitas do usuário da loja (SHOP_OWNER).
export async function GET(req: Request, ctx: { params: Promise<{ id: string }> }) {
  const { id } = await ctx.params;
  if (!/^\d+$/.test(id)) return new Response('Não encontrada', { status: 404 });
  const viewer = await getUser(); // quem é dono da receita sempre vê a própria foto (prévia no painel)
  const owner = viewer?.id ?? (await shopOwnerId());
  let row = owner ? await q1<{ data: Buffer; content_type: string }>(
    `SELECT p.data, p.content_type FROM recipe_photos p JOIN recipes r ON r.id = p.recipe_id WHERE p.recipe_id = $1 AND r.user_id = $2`,
    [id, owner],
  ) : null;
  if (!row && viewer) { // fotos da loja também são públicas para quem está logado
    const shop = await shopOwnerId();
    if (shop) row = await q1(`SELECT p.data, p.content_type FROM recipe_photos p JOIN recipes r ON r.id = p.recipe_id WHERE p.recipe_id = $1 AND r.user_id = $2`, [id, shop]);
  }
  if (!row) return new Response('Não encontrada', { status: 404 });
  const versioned = new URL(req.url).searchParams.has('v'); // a URL muda a cada foto nova, então pode guardar em cache por muito tempo
  return new Response(new Uint8Array(row.data), {
    headers: {
      'content-type': row.content_type,
      'cache-control': viewer ? 'private, max-age=60' : versioned ? 'public, max-age=31536000, immutable' : 'public, max-age=300',
      'x-content-type-options': 'nosniff',
    },
  });
}
