import { getMenu } from '@/lib/shop-menu';
import { cardapioStory } from '@/lib/story';

// Story do cardápio (PNG 1080×1920) para postar no Instagram. Só mostra o que já é público no cardápio.
export async function GET() {
  const menu = await getMenu();
  if (!menu.items.length) return Response.json({ error: 'O cardápio está vazio.' }, { status: 404 });
  const res = cardapioStory(menu.items);
  res.headers.set('Content-Disposition', 'inline; filename="story-cardapio.png"');
  res.headers.set('Cache-Control', 'public, max-age=300');
  return res;
}
