import { fail, ok, route } from '@/lib/api';
import { q1 } from '@/lib/db';
import { cleanText } from '@shared/text';

/** Conclui uma solicitação com a resposta que o cliente verá em "Meus dados". */
export const POST = route(async (req, user, { id }) => {
  const b = await req.json().catch(() => ({}));
  const answer = cleanText(b.answer).slice(0, 1000);
  if (answer.length < 3) return fail('Escreva a resposta ao cliente.');
  const r = await q1(
    `UPDATE privacy_requests SET status = 'concluida', answer = $3, answered_at = now() WHERE id = $1 AND owner_id = $2 AND status = 'aberta' RETURNING id`,
    [Number(id), user.id, answer],
  );
  return r ? ok() : fail('Solicitação não encontrada ou já concluída.', 404);
});
