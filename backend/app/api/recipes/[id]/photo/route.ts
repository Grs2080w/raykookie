import { fail, ok, route } from '@/lib/api';
import { q, q1 } from '@/lib/db';
import { sniffImage as sniff } from '@/lib/image';

const MAX_BYTES = 2_000_000;

export const PUT = route(async (req, user, { id }) => {
  if (!(await q1('SELECT 1 FROM recipes WHERE id=$1 AND user_id=$2', [id, user.id]))) return fail('Receita não encontrada.', 404);
  const buf = Buffer.from(await req.arrayBuffer());
  if (buf.length === 0) return fail('Nenhuma imagem enviada.');
  if (buf.length > MAX_BYTES) return fail('A foto é grande demais (máximo de 2 MB).', 413);
  const type = sniff(buf);
  if (!type) return fail('Use uma foto JPG, PNG ou WebP.', 415);
  await q(
    `INSERT INTO recipe_photos(recipe_id, content_type, data) VALUES ($1,$2,$3)
     ON CONFLICT (recipe_id) DO UPDATE SET content_type=EXCLUDED.content_type, data=EXCLUDED.data, updated_at=now()`,
    [id, type, buf],
  );
  return ok();
});

export const DELETE = route(async (_req, user, { id }) => {
  await q('DELETE FROM recipe_photos WHERE recipe_id=$1 AND recipe_id IN (SELECT id FROM recipes WHERE user_id=$2)', [id, user.id]);
  return ok();
});
