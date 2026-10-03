import { fail, ok } from '@/lib/api';
import { customerRoute } from '@/lib/customer-api';
import { q } from '@/lib/db';

const token = (v: unknown) => {
  const t = String(v ?? '').trim();
  return t.length >= 20 && t.length <= 4096 ? t : null;
};

// O app da loja registra o celular para receber os avisos do pedido (Pix confirmado, pronto, cancelamento, devolução).
export const POST = customerRoute(async (req, c) => {
  const t = token((await req.json().catch(() => ({})))?.token);
  if (!t) return fail('Token do aparelho inválido.');
  await q(
    `INSERT INTO customer_devices(customer_id, token) VALUES ($1,$2)
     ON CONFLICT (token) DO UPDATE SET customer_id = $1, last_seen = now()`, [c.id, t]);
  return ok();
});

// Ao sair da conta: este celular para de receber os avisos.
export const DELETE = customerRoute(async (req, c) => {
  const t = token(new URL(req.url).searchParams.get('token'));
  if (!t) return fail('Token do aparelho inválido.');
  await q('DELETE FROM customer_devices WHERE token = $1 AND customer_id = $2', [t, c.id]);
  return ok();
});
