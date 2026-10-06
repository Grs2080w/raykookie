// Feedback do cliente: nota (1-5), mensagem e fotos opcionais. Só o cliente dono do feedback e a confeiteira (owner)
// enxergam as fotos — mesmo padrão de refunds.ts/refund_photos.
import { q, q1, tx } from './db';
import { cleanText } from '@shared/text';
import { MAX_FEEDBACK_MESSAGE, MAX_FEEDBACK_PHOTOS, MAX_FEEDBACK_PHOTO_BYTES, validateFeedback } from '@shared/feedback';

export type Fail = { ok: false; status: number; error: string };
const fail = (status: number, error: string): Fail => ({ ok: false, status, error });

export type PhotoIn = { type: string; data: Buffer };
export type FeedbackInput = { rating: unknown; message: unknown; photos: PhotoIn[] };

export async function submitFeedback(ownerId: number, customerId: number, b: FeedbackInput): Promise<{ ok: true; id: number } | Fail> {
  const rating = Math.trunc(Number(b.rating));
  const message = cleanText(b.message).replace(/\s+/g, ' ').slice(0, MAX_FEEDBACK_MESSAGE);
  const err = validateFeedback(rating, message);
  if (err) return fail(400, err);
  if (b.photos.length > MAX_FEEDBACK_PHOTOS) return fail(400, `Envie no máximo ${MAX_FEEDBACK_PHOTOS} fotos.`);
  for (const p of b.photos) if (p.data.length > MAX_FEEDBACK_PHOTO_BYTES) return fail(413, 'Uma das fotos é grande demais (máximo de 2 MB).');

  const id = await tx(async (c) => {
    const r = await c.query<{ id: number }>(
      `INSERT INTO feedback (owner_id, customer_id, rating, message) VALUES ($1,$2,$3,$4) RETURNING id`,
      [ownerId, customerId, rating, message],
    );
    const fid = r.rows[0].id;
    let idx = 1;
    for (const p of b.photos) {
      await c.query(
        `INSERT INTO feedback_photos (feedback_id, idx, content_type, data, size) VALUES ($1,$2,$3,$4,$5)`,
        [fid, idx++, p.type, p.data, p.data.length],
      );
    }
    return fid;
  });
  return { ok: true, id };
}

export type FeedbackRow = {
  id: number; rating: number; message: string; created_at: string;
  customer_name: string; customer_email: string; photos: number;
};

/** Lista da confeiteira, mais recentes primeiro. */
export async function listFeedback(ownerId: number, limit = 100): Promise<FeedbackRow[]> {
  return q<FeedbackRow>(
    `SELECT f.id, f.rating, f.message, f.created_at, c.name AS customer_name, c.email AS customer_email,
            (SELECT count(*)::int FROM feedback_photos p WHERE p.feedback_id = f.id) AS photos
       FROM feedback f JOIN customers c ON c.id = f.customer_id
      WHERE f.owner_id = $1
      ORDER BY f.created_at DESC LIMIT $2`,
    [ownerId, limit],
  );
}

export async function getFeedbackPhoto(feedbackId: number, idx: number, ownerId: number): Promise<{ content_type: string; data: Buffer } | null> {
  return q1<{ content_type: string; data: Buffer }>(
    `SELECT p.content_type, p.data FROM feedback_photos p JOIN feedback f ON f.id = p.feedback_id
      WHERE f.id = $1 AND p.idx = $2 AND f.owner_id = $3`,
    [feedbackId, idx, ownerId],
  );
}

/** Mesma foto, mas o próprio cliente que enviou também pode vê-la (ex.: tela "Meus pedidos" / histórico). */
export async function getFeedbackPhotoForCustomer(feedbackId: number, idx: number, customerId: number): Promise<{ content_type: string; data: Buffer } | null> {
  return q1<{ content_type: string; data: Buffer }>(
    `SELECT p.content_type, p.data FROM feedback_photos p JOIN feedback f ON f.id = p.feedback_id
      WHERE f.id = $1 AND p.idx = $2 AND f.customer_id = $3`,
    [feedbackId, idx, customerId],
  );
}
