import { fail, ok, route } from '@/lib/api';
import { q, q1 } from '@/lib/db';

const MAX_BYTES = 2_000_000;

// Confere o tipo pelo conteúdo do arquivo, não pelo que o navegador diz.
function sniff(b: Buffer): 'image/jpeg' | 'image/png' | 'image/webp' | null {
  if (b.length > 12 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg';
  if (b.length > 12 && b.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'image/png';
  if (b.length > 12 && b.subarray(0, 4).toString() === 'RIFF' && b.subarray(8, 12).toString() === 'WEBP') return 'image/webp';
  return null;
}

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
