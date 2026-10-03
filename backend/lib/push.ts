import { PRIVACY_DEADLINE_DAYS, privacyLabel } from '@shared/lgpd';
import { q } from './db';
import { DEFAULT_ORDER_SOUND, isOrderSound, orderChannelId, type OrderSoundId } from '@shared/push';

export type PushMessage = {
  token: string;
  notification: { title: string; body: string };
  data: Record<string, string>;
  android: { priority: 'high'; notification: { channelId: string; sound: string } };
};

// ---- app do cliente: um canal só ("Seus pedidos"), som padrão do celular. Tocar abre Meus pedidos, no pedido.
export const CUSTOMER_CHANNEL = 'pedidos_cliente';
export type CustomerPush = { orderId: number; code: number; type: string; title: string; body: string };
export function customerMessage(token: string, m: CustomerPush): PushMessage {
  return {
    token,
    notification: { title: m.title, body: m.body },
    data: { type: m.type, orderId: String(m.orderId), audience: 'cliente' },
    android: { priority: 'high', notification: { channelId: CUSTOMER_CHANNEL, sound: 'default' } },
  };
}
export type PushResult = { token: string; ok: boolean; dead: boolean };
export type PushSender = (messages: PushMessage[]) => Promise<PushResult[]>;

const brl = (n: number) => `R$ ${Number(n).toFixed(2).replace('.', ',')}`;

export function newOrderMessage(token: string, sound: string, o: { id: number; code?: number; customer: string; total: number; items: number }): PushMessage {
  const s: OrderSoundId = isOrderSound(sound) ? sound : DEFAULT_ORDER_SOUND;
  const itens = `${o.items} ${o.items === 1 ? 'item' : 'itens'}`;
  return {
    token,
    notification: { title: `Novo pedido #${o.code ?? o.id}`, body: `${o.customer} · ${brl(o.total)} · ${itens}` },
    data: { type: 'novo_pedido', orderId: String(o.id) },
    // o som no Android 8+ é do canal; o app cria um canal por som e o push escolhe qual usar
    android: { priority: 'high', notification: { channelId: orderChannelId(s), sound: `pedido_${s}` } },
  };
}

type PaidOrder = { id: number; code?: number; customer: string; total: number; items: number };
// Pagamento confirmado usa o canal "caixa registradora", que todo app de gestão já tem (assim funciona sem atualizar o app) e soa diferente de pedido novo.
const PAID_SOUND: OrderSoundId = 'caixa';

export function paymentMessage(token: string, o: PaidOrder): PushMessage {
  const itens = `${o.items} ${o.items === 1 ? 'item' : 'itens'}`;
  return {
    token,
    notification: { title: `Pix recebido · Pedido #${o.code ?? o.id}`, body: `${o.customer} pagou ${brl(o.total)} · ${itens}` },
    data: { type: 'pagamento', orderId: String(o.id) },
    android: { priority: 'high', notification: { channelId: orderChannelId(PAID_SOUND), sound: `pedido_${PAID_SOUND}` } },
  };
}

type CancelledOrder = { id: number; code?: number; customer: string; total: number; reason: string; refunded?: number };
const CANCEL_SOUND: OrderSoundId = 'alerta';

export function cancelMessage(token: string, o: CancelledOrder): PushMessage {
  const why = o.reason ? ` · ${o.reason.slice(0, 80)}` : '';
  return {
    token,
    notification: {
      title: `Pedido #${o.code ?? o.id} cancelado pela cliente`,
      body: `${o.customer} cancelou · ${brl(o.total)}${why}${o.refunded ? `. O Pix de ${brl(o.refunded)} foi devolvido automaticamente.` : ''}`,
    },
    data: { type: 'pedido_cancelado', orderId: String(o.id) },
    android: { priority: 'high', notification: { channelId: orderChannelId(CANCEL_SOUND), sound: `pedido_${CANCEL_SOUND}` } },
  };
}

// Erros do FCM que significam "este token não existe mais".
const DEAD = ['messaging/registration-token-not-registered', 'messaging/invalid-registration-token'];

let sender: PushSender | null | undefined; // undefined = ainda não tentou; null = push desligado

async function firebaseSender(): Promise<PushSender | null> {
  // Testes de ponta a ponta: grava cada notificação numa linha JSON em vez de mandar ao Firebase.
  const sink = process.env.PUSH_LOG_PATH;
  if (sink) {
    const { appendFileSync } = await import('node:fs');
    return async (messages) => {
      appendFileSync(sink, messages.map((m) => JSON.stringify({ at: new Date().toISOString(), ...m })).join('\n') + '\n');
      return messages.map((m) => ({ token: m.token, ok: true, dead: false }));
    };
  }
  const file = process.env.FIREBASE_SERVICE_ACCOUNT_PATH;
  if (!file) return null;
  try {
    const { readFileSync } = await import('node:fs');
    const { initializeApp, getApps, cert } = await import('firebase-admin/app');
    const { getMessaging } = await import('firebase-admin/messaging');
    const app = getApps()[0] ?? initializeApp({ credential: cert(JSON.parse(readFileSync(file, 'utf8'))) });
    const messaging = getMessaging(app);
    return async (messages) => {
      const r = await messaging.sendEach(messages);
      return r.responses.map((x, i) => ({ token: messages[i].token, ok: x.success, dead: !x.success && DEAD.includes(x.error?.code ?? '') }));
    };
  } catch (e) {
    console.error('[push] Firebase não inicializou; push desligado:', (e as Error).message);
    return null;
  }
}

async function getSender() {
  if (sender === undefined) sender = await firebaseSender();
  return sender;
}

/** Só para testes: troca (ou limpa) o envio. */
export function setPushSender(s: PushSender | null | undefined) { sender = s; }

async function notifyOwner(ownerId: number, build: (token: string, sound: string) => PushMessage, what: string, orderId: number): Promise<void> {
  try {
    const send = await getSender();
    if (!send) return;
    const devices = await q<{ token: string; sound: string }>('SELECT token, sound FROM device_tokens WHERE user_id = $1', [ownerId]);
    if (!devices.length) return;
    const results = await send(devices.map((d) => build(d.token, d.sound)));
    const dead = results.filter((r) => r.dead).map((r) => r.token);
    if (dead.length) await q('DELETE FROM device_tokens WHERE token = ANY($1)', [dead]);
  } catch (e) {
    console.error(`[push] falha ao notificar ${what}`, orderId, e);
  }
}

/** Avisa todos os celulares em que o cliente está logado no app da loja. Nunca lança erro. */
async function notifyCustomer(customerId: number, msg: CustomerPush, what: string): Promise<void> {
  try {
    const send = await getSender();
    if (!send) return;
    const devices = await q<{ token: string }>('SELECT token FROM customer_devices WHERE customer_id = $1', [customerId]);
    if (!devices.length) return;
    const results = await send(devices.map((d) => customerMessage(d.token, msg)));
    const dead = results.filter((r) => r.dead).map((r) => r.token);
    if (dead.length) await q('DELETE FROM customer_devices WHERE token = ANY($1)', [dead]);
  } catch (e) {
    console.error(`[push] falha ao avisar o cliente (${what})`, msg.orderId, e);
  }
}

/** Avisa todos os celulares logados como `ownerId`. Nunca lança erro: falha de push não pode derrubar o pedido. */
export const notifyNewOrder = (ownerId: number, o: { id: number; code?: number; customer: string; total: number; items: number }) =>
  notifyOwner(ownerId, (token, sound) => newOrderMessage(token, sound, o), 'pedido', o.id);

/** Avisa que o Pix do pedido foi pago (chamado pelo webhook do Mercado Pago). */
export const notifyPaid = (ownerId: number, o: PaidOrder) => notifyOwner(ownerId, (token) => paymentMessage(token, o), 'pagamento', o.id);

/** Avisa que o cliente cancelou o pedido. */
export const notifyCancelled = (ownerId: number, o: CancelledOrder) => notifyOwner(ownerId, (token) => cancelMessage(token, o), 'cancelamento', o.id);

/** Avisa que um cliente fez uma solicitação sobre os próprios dados (LGPD: a resposta tem prazo de 15 dias). */
export function privacyMessage(token: string, o: { id: number; type: string }): PushMessage {
  return {
    token,
    notification: { title: 'Solicitação de privacidade', body: `${privacyLabel(o.type)}. Responda em até ${PRIVACY_DEADLINE_DAYS} dias.` },
    data: { type: 'privacidade', requestId: String(o.id) },
    android: { priority: 'high', notification: { channelId: orderChannelId(CANCEL_SOUND), sound: `pedido_${CANCEL_SOUND}` } },
  };
}
export const notifyPrivacyRequest = (ownerId: number, o: { id: number; type: string }) => notifyOwner(ownerId, (token) => privacyMessage(token, o), 'privacidade', o.id);

export type RefundPush =
  | { kind: 'solicitada'; refundId: number; code: number; customer: string; amount: number; priority: boolean; reason?: string; photos?: number }
  | { kind: 'desistiu'; refundId: number; code: number; customer: string }
  | { kind: 'concluida'; refundId: number; code: number; amount: number }
  | { kind: 'saldo' | 'falha'; refundId: number; code: number; amount: number; stalled: boolean }
  | { kind: 'tardio'; refundId: number; code: number };

export function refundMessage(token: string, o: RefundPush): PushMessage {
  const base = { token, data: { type: 'devolucao', refundId: String(o.refundId) } as Record<string, string> };
  const alerta = { priority: 'high' as const, notification: { channelId: orderChannelId(CANCEL_SOUND), sound: `pedido_${CANCEL_SOUND}` } };
  switch (o.kind) {
    case 'solicitada':
      return {
        ...base,
        notification: {
          title: `${o.priority ? 'Urgente: ' : ''}Pedido de devolução #${o.code}`,
          body: `${o.customer} pede ${brl(o.amount)} de volta${o.reason ? `: ${o.reason.charAt(0).toLowerCase()}${o.reason.slice(1)}` : ''}.${o.photos ? ` ${o.photos} foto${o.photos > 1 ? 's' : ''}.` : ''} Toque para decidir.`,
        },
        android: alerta,
      };
    case 'desistiu':
      return { ...base, notification: { title: `Cliente desistiu da devolução #${o.code}`, body: `${o.customer} cancelou a solicitação antes da sua análise.` }, android: alerta };
    case 'concluida':
      return { ...base, notification: { title: `Devolução concluída #${o.code}`, body: `${brl(o.amount)} devolvidos depois da nova tentativa.` }, android: alerta };
    case 'saldo':
      return { ...base, notification: { title: 'Devolução parada: falta saldo', body: `Pedido #${o.code}: coloque ${brl(o.amount)} no Mercado Pago. ${o.stalled ? 'Toque para tentar de novo.' : 'Tentamos de novo mais tarde.'}` }, android: alerta };
    case 'falha':
      return { ...base, notification: { title: 'Devolução não concluída', body: `Pedido #${o.code} (${brl(o.amount)}). ${o.stalled ? 'Toque para tentar de novo.' : 'Tentamos de novo mais tarde.'}` }, android: alerta };
    case 'tardio':
      return { ...base, notification: { title: `Pix pago depois do cancelamento #${o.code}`, body: 'Devolvemos o valor automaticamente ao cliente.' }, android: alerta };
  }
}
export const notifyOwnerRefund = (ownerId: number, o: RefundPush) => notifyOwner(ownerId, (token) => refundMessage(token, o), 'devolução', o.refundId);

export type PriceAlertPush = { recipeId: number; name: string; kind: 'margem' | 'preco'; before: number; after: number; cause: string };
export function priceAlertMessage(token: string, o: PriceAlertPush): PushMessage {
  const f = (n: number) => String(Math.round(n * 10) / 10).replace('.', ',');
  const body = o.kind === 'margem'
    ? `A margem de ${o.name} caiu de ${f(o.before)}% para ${f(o.after)}%. ${o.cause}`
    : `O preço de ${o.name} sobe de ${brl(o.before)} para ${brl(o.after)}. ${o.cause}`;
  return { token, notification: { title: o.kind === 'margem' ? 'Margem caiu' : 'Preço do cardápio vai subir', body }, data: { type: 'preco', recipeId: String(o.recipeId) }, android: { priority: 'high', notification: { channelId: orderChannelId(CANCEL_SOUND), sound: `pedido_${CANCEL_SOUND}` } } };
}
export const notifyPriceAlert = (ownerId: number, o: PriceAlertPush) => notifyOwner(ownerId, (token) => priceAlertMessage(token, o), 'preço', o.recipeId);

// ---- avisos ao cliente (app da loja)
const windowLabel: Record<string, string> = { manha: 'da manhã', tarde: 'da tarde', noite: 'da noite' };
export type CustomerOrder = { id: number; code: number; customerId: number; total: number; fulfillment?: string; timeWindow?: string; paid?: boolean };

export const notifyCustomerPaid = (o: CustomerOrder) =>
  notifyCustomer(o.customerId, { orderId: o.id, code: o.code, type: 'pagamento', title: `Pix confirmado · #${o.code}`, body: `Recebemos o pagamento de ${brl(o.total)}. Seu pedido entra na fila de produção.` }, 'pagamento');

/** Mudança de etapa do pedido (em preparo, pronto, entregue). */
export function customerStatusPush(o: CustomerOrder, status: string): CustomerPush | null {
  const base = { orderId: o.id, code: o.code, type: 'status' };
  if (status === 'producao') return { ...base, title: `Pedido em preparo · #${o.code}`, body: 'Começamos a preparar o seu pedido.' };
  if (status === 'pronto') {
    return { ...base, title: `Pedido pronto · #${o.code}`, body: o.fulfillment === 'entrega'
      ? `Ele sai para entrega no período ${windowLabel[o.timeWindow ?? ''] ?? 'combinado'}.`
      : `Pode retirar no endereço combinado, no período ${windowLabel[o.timeWindow ?? ''] ?? 'combinado'}.` };
  }
  if (status === 'entregue') {
    return { ...base, title: `Pedido entregue · #${o.code}`, body: o.paid
      ? 'Bom apetite! Se algo não estiver certo, você pode pedir devolução em até 48h pelo app.'
      : 'Bom apetite! Obrigada pela encomenda.' };
  }
  return null;
}
export const notifyCustomerStatus = (o: CustomerOrder, status: string) => {
  const m = customerStatusPush(o, status);
  return m ? notifyCustomer(o.customerId, m, 'status') : Promise.resolve();
};

export const notifyCustomerStoreCancel = (o: CustomerOrder, reason: string, refunded: number) =>
  notifyCustomer(o.customerId, {
    orderId: o.id, code: o.code, type: 'cancelado',
    title: `Pedido cancelado pela loja · #${o.code}`,
    body: `Motivo: ${reason}.${refunded > 0 ? ` ${brl(refunded)} voltam por Pix.` : ''}`,
  }, 'cancelamento');

export type CustomerRefundPush =
  | { kind: 'aprovada'; amount: number; requested: number }
  | { kind: 'negada'; reason: string }
  | { kind: 'concluida'; amount: number };
export function customerRefundPush(o: Pick<CustomerOrder, 'id' | 'code'>, r: CustomerRefundPush): CustomerPush {
  const base = { orderId: o.id, code: o.code, type: 'devolucao' };
  switch (r.kind) {
    case 'aprovada':
      return { ...base, title: `Devolução aprovada · #${o.code}`, body: r.amount < r.requested ? `${brl(r.amount)} de ${brl(r.requested)}. Veja a mensagem da loja.` : `${brl(r.amount)} voltam por Pix.` };
    case 'negada':
      return { ...base, title: `Devolução negada · #${o.code}`, body: `Motivo: ${r.reason}.` };
    case 'concluida':
      return { ...base, title: `Pix devolvido · #${o.code}`, body: `${brl(r.amount)} voltaram para a conta que pagou.` };
  }
}
export const notifyCustomerRefund = (customerId: number, o: Pick<CustomerOrder, 'id' | 'code'>, r: CustomerRefundPush) =>
  notifyCustomer(customerId, customerRefundPush(o, r), 'devolução');
