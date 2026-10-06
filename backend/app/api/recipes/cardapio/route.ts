import { getRecipes } from '@/lib/queries';
import { ok, route } from '@/lib/api';

// Lista enxuta para o editor de cardápio: toda receita do usuário (precificada ou não), com o que o
// editor precisa pra mostrar e deixar ligar/desligar — sem o cálculo de preço completo.
export const GET = route(async (_req, user) => {
  const rows = await getRecipes(user.id);
  return ok(rows.map((r) => ({
    id: r.id, name: r.name, show_on_menu: r.show_on_menu, tags: r.tags,
    has_photo: r.photo_v != null,
    priced: r.sale_price != null || r.items.length > 0, // dá pra aparecer no cardápio? (ver getMenu: precisa de preço > 0)
  })));
});
