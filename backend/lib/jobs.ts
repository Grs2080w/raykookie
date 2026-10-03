import { q } from './db';

/** Apaga os bytes das fotos de devoluções decididas há mais de 180 dias; o registro da devolução fica. */
export async function cleanupOldRefundPhotos(): Promise<number> {
  const rows = await q(
    `DELETE FROM refund_photos WHERE refund_id IN (
       SELECT id FROM refunds WHERE status IN ('concluido','negado','desistiu') AND COALESCE(decided_at, refunded_at, created_at) < now() - interval '180 days'
     ) RETURNING id`);
  return rows.length;
}
