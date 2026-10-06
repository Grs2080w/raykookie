import { ok, fail, route } from '@/lib/api';
import { q1 } from '@/lib/db';
import { cleanMenuTags } from '@shared/menu-tags';

// Editor de cardápio: liga/desliga a visibilidade e as características (tags fixas) de uma receita,
// sem mexer no resto dela (preço, ingredientes etc. continuam só em PUT /api/recipes/:id).
export const PATCH = route(async (req, user, { id }) => {
  const b = await req.json();
  const showOnMenu = typeof b.show_on_menu === 'boolean' ? b.show_on_menu : null;
  const tags = b.tags === undefined ? null : cleanMenuTags(b.tags);
  if (showOnMenu === null && tags === null) return fail('Nada para atualizar.', 400);
  const row = await q1<{ id: number; show_on_menu: boolean; tags: string[] }>(
    `UPDATE recipes SET show_on_menu = COALESCE($3, show_on_menu), tags = COALESCE($4, tags)
      WHERE id = $1 AND user_id = $2 RETURNING id, show_on_menu, tags`,
    [Number(id), user.id, showOnMenu, tags],
  );
  return row ? ok(row) : fail('Receita não encontrada.', 404);
});
