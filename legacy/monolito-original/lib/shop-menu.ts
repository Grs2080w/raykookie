// Cardápio da loja = receitas do painel do SHOP_OWNER. Só nome, preço por unidade e foto saem daqui.
import { getCategories, getPricedRecipes } from './queries';
import { shopOwnerId } from './shop-queries';
import type { MenuGroup, MenuItem } from './shop';

const slug = (s: string) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'outros';

export async function getMenu(): Promise<{ items: MenuItem[]; groups: MenuGroup[] }> {
  try {
    const owner = await shopOwnerId();
    if (!owner) return { items: [], groups: [] };
    const [recipes, cats] = await Promise.all([getPricedRecipes(owner), getCategories(owner, 'recipe')]);
    const catName = new Map(cats.map((c) => [c.id, c.name]));
    const items: MenuItem[] = recipes
      .filter((r) => r.pricing.rounded > 0) // receita sem ingredientes (preço zerado) não vai para a loja
      .map((r) => ({
        id: String(r.id), name: r.name, price: r.pricing.rounded,
        category: (r.category_id && catName.get(r.category_id)) || 'Outros',
        ...(r.photo_v ? { image: `/api/fotos/${r.id}?v=${r.photo_v}` } : {}),
      }));
    const byCat = new Map<string, MenuItem[]>();
    for (const it of items) byCat.set(it.category, [...(byCat.get(it.category) ?? []), it]);
    const groups = [...byCat].sort(([a], [b]) => (a === 'Outros' ? 1 : b === 'Outros' ? -1 : a.localeCompare(b, 'pt-BR')))
      .map(([name, list]) => ({ id: slug(name), name, items: list.sort((x, y) => x.name.localeCompare(y.name, 'pt-BR')) }));
    return { items, groups };
  } catch (e) {
    console.error('getMenu', e);
    return { items: [], groups: [] };
  }
}
