// Direitos do titular (LGPD) no app e no site do cliente: ver, corrigir, exportar, solicitar e excluir a conta.
// Excluir = anonimizar: pedidos e pagamentos ficam (obrigação fiscal), sem nenhum dado que identifique a pessoa.
import { q, q1, tx } from './db';
import { verifyPassword } from './auth';
import { shopOwnerId } from './shop-queries';
import { PRIVACY_TYPES, type PrivacyType } from '@shared/lgpd';

type Row = Record<string, any>;

/** Pedidos que ainda não foram entregues nem cancelados: com eles em andamento a conta não pode ser excluída. */
export async function openOrderCount(customerId: number): Promise<number> {
  const r = await q1<{ n: number }>(
    `SELECT count(DISTINCT so.id)::int AS n FROM shop_orders so
       JOIN orders o ON o.shop_order_id = so.id
      WHERE so.customer_id = $1 AND so.cancelled_at IS NULL AND o.status NOT IN ('entregue','cancelado')`,
    [customerId],
  );
  return r?.n ?? 0;
}

/** "Meus dados": tudo o que guardamos sobre a pessoa, em palavras simples. */
export async function getMyData(customerId: number) {
  const c = await q1<Row>(
    `SELECT id, email, name, phone, cep, street, number, complement, district, city, created_at,
            (password_hash IS NOT NULL) AS has_password, (google_sub IS NOT NULL) AS has_google
       FROM customers WHERE id = $1 AND deleted_at IS NULL`,
    [customerId],
  );
  if (!c) return null;
  const orders = await q1<{ n: number }>('SELECT count(*)::int AS n FROM shop_orders WHERE customer_id = $1', [customerId]);
  const open = await q1<{ n: number }>(`SELECT count(*)::int AS n FROM privacy_requests WHERE customer_id = $1 AND status = 'aberta'`, [customerId]);
  return { ...c, orders: orders?.n ?? 0, open_requests: open?.n ?? 0, open_orders: await openOrderCount(customerId) };
}

/** Cópia dos dados em formato aberto (portabilidade). Só do próprio cliente. */
export async function exportData(customerId: number) {
  const account = await getMyData(customerId);
  const orders = await q<Row>(
    `SELECT id, public_code AS code, created_at, scheduled_for, time_window, fulfillment, cep, street, number, complement, district, city,
            payment_method, total, items, notes, paid_at, cancelled_at, cancel_reason, pickup_address
       FROM shop_orders WHERE customer_id = $1 ORDER BY created_at`,
    [customerId],
  );
  const requests = await q<Row>('SELECT type, details, status, answer, created_at, answered_at FROM privacy_requests WHERE customer_id = $1 ORDER BY created_at', [customerId]);
  const devices = await q<Row>('SELECT platform, created_at, last_seen FROM customer_devices WHERE customer_id = $1 ORDER BY created_at', [customerId]);
  return { exported_at: new Date().toISOString(), account, orders, privacy_requests: requests, notification_devices: devices };
}

const onlyDigits = (s: unknown) => String(s ?? '').replace(/\D/g, '');

export type ProfileInput = { name?: unknown; phone?: unknown; cep?: unknown; street?: unknown; number?: unknown; complement?: unknown; district?: unknown; city?: unknown };
export async function updateProfile(customerId: number, b: ProfileInput): Promise<{ error?: string }> {
  const t = (v: unknown, n: number) => String(v ?? '').replace(/\s+/g, ' ').trim().slice(0, n);
  const name = t(b.name, 80);
  const phone = onlyDigits(b.phone);
  const cep = onlyDigits(b.cep);
  if (name.length < 2) return { error: 'Informe seu nome.' };
  if (phone && ![10, 11].includes(phone.length)) return { error: 'Telefone inválido. Use DDD + número.' };
  if (cep && cep.length !== 8) return { error: 'CEP inválido.' };
  await q(
    `UPDATE customers SET name=$2, phone=$3, cep=$4, street=$5, number=$6, complement=$7, district=$8, city=$9 WHERE id=$1 AND deleted_at IS NULL`,
    [customerId, name, phone, cep, t(b.street, 120), t(b.number, 20), t(b.complement, 60), t(b.district, 80), t(b.city, 80)],
  );
  return {};
}

/** Retira o vínculo com o Google. Só se houver senha, senão a pessoa ficaria sem como entrar. */
export async function unlinkGoogle(customerId: number): Promise<{ error?: string }> {
  const c = await q1<Row>('SELECT password_hash IS NOT NULL AS has_password, google_sub IS NOT NULL AS has_google FROM customers WHERE id = $1', [customerId]);
  if (!c?.has_google) return { error: 'Sua conta não está ligada ao Google.' };
  if (!c.has_password) return { error: 'Sua conta só entra pelo Google. Para desvincular, exclua a conta ou peça uma solicitação de dados.' };
  await q('UPDATE customers SET google_sub = NULL WHERE id = $1', [customerId]);
  return {};
}

export async function createPrivacyRequest(customerId: number, type: unknown, details: unknown): Promise<{ error?: string; id?: number }> {
  if (!PRIVACY_TYPES.some((t) => t.id === type)) return { error: 'Escolha o tipo de solicitação.' };
  const text = String(details ?? '').replace(/\s+/g, ' ').trim().slice(0, 1000);
  if (type === 'outro' && text.length < 10) return { error: 'Conte um pouco mais sobre o que você precisa.' };
  const owner = await shopOwnerId();
  if (!owner) return { error: 'Serviço indisponível no momento.' };
  const r = await q1<{ id: number }>(
    'INSERT INTO privacy_requests(owner_id, customer_id, type, details) VALUES ($1,$2,$3,$4) RETURNING id',
    [owner, customerId, type as PrivacyType, text],
  );
  return { id: r!.id };
}

export async function myRequests(customerId: number) {
  return q<Row>('SELECT id, type, details, status, answer, created_at, answered_at FROM privacy_requests WHERE customer_id = $1 ORDER BY created_at DESC LIMIT 20', [customerId]);
}

export type DeleteResult = { ok: true } | { ok: false; status: number; error: string };
/** Exclui a conta: anonimiza a pessoa e os pedidos, apaga sessões e fotos. Pedidos e valores continuam. */
export async function deleteAccount(customerId: number, confirm: unknown, password: unknown): Promise<DeleteResult> {
  if (String(confirm ?? '').trim().toUpperCase() !== 'EXCLUIR') return { ok: false, status: 400, error: 'Digite EXCLUIR para confirmar.' };
  const c = await q1<{ password_hash: string | null }>('SELECT password_hash FROM customers WHERE id = $1 AND deleted_at IS NULL', [customerId]);
  if (!c) return { ok: false, status: 404, error: 'Conta não encontrada.' };
  if (c.password_hash && !verifyPassword(String(password ?? ''), c.password_hash)) return { ok: false, status: 403, error: 'Senha incorreta.' };
  const open = await openOrderCount(customerId);
  if (open > 0) return { ok: false, status: 409, error: `Você tem ${open} pedido${open > 1 ? 's' : ''} em andamento. Quando forem entregues (ou se você cancelá-los), é só voltar aqui e excluir a conta.` };
  const owner = await shopOwnerId();
  await tx(async (c2) => {
    await c2.query(
      `UPDATE orders o SET customer = 'Cliente excluído', address = '', notes = 'Pedido online #' || so.public_code
         FROM shop_orders so WHERE o.shop_order_id = so.id AND so.customer_id = $1`,
      [customerId],
    );
    await c2.query(
      `UPDATE shop_orders SET contact_name = 'Cliente excluído', phone = '', cep = '', street = '', number = '', complement = '', district = '', city = '', notes = '', pickup_address = '', cancel_note = ''
       WHERE customer_id = $1`,
      [customerId],
    );
    await c2.query('DELETE FROM customer_sessions WHERE customer_id = $1', [customerId]);
    await c2.query('DELETE FROM customer_devices WHERE customer_id = $1', [customerId]); // nenhum aviso chega mais a este cliente
    await c2.query(
      `UPDATE customers SET email = 'excluido-' || id || '@invalid', name = 'Cliente excluído', phone = '', cep = '', street = '', number = '', complement = '', district = '', city = '',
              password_hash = NULL, google_sub = NULL, deleted_at = now() WHERE id = $1`,
      [customerId],
    );
    if (owner) await c2.query(`INSERT INTO privacy_requests(owner_id, customer_id, type, details, status, answer, answered_at) VALUES ($1,$2,'exclusao','Exclusão feita pelo próprio cliente','concluida','Conta anonimizada automaticamente.', now())`, [owner, customerId]);
  });
  return { ok: true };
}
