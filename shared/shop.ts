// Loja online: regras do pedido e validação. Puro (roda no cliente e no servidor).
// O cardápio e os preços vêm das receitas do painel (lib/shop-menu.ts); o servidor sempre recalcula o total por eles.
import { addDays, cleanText, isoDate, parseLocaleNumber } from './text';

// Item do cardápio = uma receita do painel: só nome, preço por unidade e foto (nada de ingredientes, custos ou margem).
export type { MenuItem, MenuGroup } from './types';
import type { MenuItem } from './types';

/** Qual ilustração usar quando a receita não tem foto, pelo nome/categoria. */
export function photoKind(item: Pick<MenuItem, 'name' | 'category'>): 'cookie' | 'torta' | 'brownie' | 'bolo' | 'bancada' {
  const t = `${item.name} ${item.category}`.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  if (/cookie|biscoito/.test(t)) return 'cookie';
  if (/torta|pie|tarte/.test(t)) return 'torta';
  if (/brownie|brigadeiro|docinho/.test(t)) return 'brownie';
  if (/bolo|cupcake/.test(t)) return 'bolo';
  return 'bancada';
}

export const FULFILLMENT = [
  { id: 'retirada', label: 'Retirar no local', hint: 'Sem taxa' },
  { id: 'entrega', label: 'Receber em casa', hint: 'Se houver taxa de entrega, avisamos pelo WhatsApp' },
] as const;
export const PAYMENT_METHODS = [
  { id: 'pix', label: 'Pix', hint: 'Geramos o Pix copia e cola ao finalizar o pedido.' },
  { id: 'dinheiro', label: 'Dinheiro', hint: 'Informe se precisa de troco.' },
] as const;
/** Rótulo para exibir; mantém "Cartão" só para pedidos antigos (não é mais opção no checkout). */
export const paymentLabel = (id: string) => (id === 'cartao' ? 'Cartão' : PAYMENT_METHODS.find((p) => p.id === id)?.label ?? id);
export const TIME_WINDOWS = [
  { id: 'manha', label: 'Manhã (8h às 12h)' },
  { id: 'tarde', label: 'Tarde (12h às 18h)' },
  { id: 'noite', label: 'Noite (18h às 21h)' },
] as const;

/** Antecedência mínima da encomenda, em dias. */
export const LEAD_DAYS = 1;
/** "1 dia de antecedência" / "2 dias de antecedência" — LEAD_DAYS já com o plural certo. */
export const leadDaysLabel = () => `${LEAD_DAYS} ${LEAD_DAYS === 1 ? 'dia' : 'dias'} de antecedência`;
export const MAX_QTY = 50;

// Status que o cliente enxerga (vêm do painel: novo, producao, pronto, entregue, cancelado).
export const CUSTOMER_STATUS: Record<string, { label: string; tone: 'neutral' | 'warn' | 'ok' | 'bad' }> = {
  novo: { label: 'Recebido', tone: 'neutral' },
  producao: { label: 'Em preparo', tone: 'warn' },
  pronto: { label: 'Pronto', tone: 'ok' },
  entregue: { label: 'Entregue', tone: 'ok' },
  cancelado: { label: 'Cancelado', tone: 'bad' },
};
const RANK = ['novo', 'producao', 'pronto', 'entregue'];
/** Situação geral do pedido a partir da situação de cada item: a do item menos adiantado (ignorando cancelados). */
export function overallStatus(statuses: string[] | null | undefined): string {
  const s = (statuses ?? []).filter(Boolean);
  if (s.length === 0) return 'cancelado';
  const live = s.filter((x) => x !== 'cancelado');
  if (live.length === 0) return 'cancelado';
  return live.reduce((a, b) => (RANK.indexOf(a) <= RANK.indexOf(b) ? a : b));
}

export type CartLine = { id: string; qty: number };
export const cartTotal = (lines: CartLine[], menu: MenuItem[]) => lines.reduce((s, l) => s + (menu.find((m) => m.id === l.id)?.price ?? 0) * l.qty, 0);

// ---- campos com máscara -------------------------------------------------------------------------
export const onlyDigits = (v: string, max = 20) => String(v ?? '').replace(/\D/g, '').slice(0, max);
export function formatPhone(v: string) {
  const d = onlyDigits(v, 11);
  if (d.length <= 2) return d;
  if (d.length <= 6) return `(${d.slice(0, 2)}) ${d.slice(2)}`;
  if (d.length <= 10) return `(${d.slice(0, 2)}) ${d.slice(2, 6)}-${d.slice(6)}`;
  return `(${d.slice(0, 2)}) ${d.slice(2, 7)}-${d.slice(7)}`;
}
export const formatCep = (v: string) => { const d = onlyDigits(v, 8); return d.length > 5 ? `${d.slice(0, 5)}-${d.slice(5)}` : d; };

// ---- validação do pedido ------------------------------------------------------------------------
export type ShopOrderInput = {
  contact_name: string; phone: string; fulfillment: 'retirada' | 'entrega';
  cep: string; street: string; number: string; complement: string; district: string; city: string;
  scheduled_for: string; time_window: string; payment_method: string; change_for: number | null; notes: string;
  items: { id: string; name: string; qty: number; unit_price: number }[]; total: number;
};

const pick = <T extends { id: string }>(list: readonly T[], id: string) => list.find((x) => x.id === id)?.id;

/** Confere tudo o que veio do formulário. `today` é a data de hoje (AAAA-MM-DD) no fuso da loja. */
export function parseShopOrder(b: any, today: string, menu: MenuItem[]): { error?: string; value?: ShopOrderInput } {
  const raw = Array.isArray(b?.items) ? b.items : [];
  const merged = new Map<string, number>();
  for (const it of raw) {
    const qty = Number(it?.qty);
    const id = cleanText(it?.id);
    if (!menu.some((m) => m.id === id)) return { error: 'Um dos itens escolhidos não está mais no cardápio.' };
    if (!Number.isInteger(qty) || qty < 1 || qty > MAX_QTY) return { error: `A quantidade de cada item vai de 1 a ${MAX_QTY}.` };
    merged.set(id, (merged.get(id) ?? 0) + qty);
  }
  if (merged.size === 0) return { error: 'Escolha pelo menos um item do cardápio.' };
  const items = [...merged].map(([id, qty]) => { const m = menu.find((x) => x.id === id)!; return { id, name: m.name, qty: Math.min(qty, MAX_QTY), unit_price: m.price }; });
  const total = Math.round(items.reduce((s, i) => s + i.qty * i.unit_price, 0) * 100) / 100;

  const contact_name = cleanText(b?.contact_name).slice(0, 80);
  if (contact_name.length < 2) return { error: 'Informe seu nome.' };
  const phone = onlyDigits(b?.phone, 11);
  if (phone.length < 10) return { error: 'Informe um telefone com DDD. Só usamos se precisarmos falar com você sobre o pedido.' };

  const fulfillment = pick(FULFILLMENT, cleanText(b?.fulfillment)) as 'retirada' | 'entrega' | undefined;
  if (!fulfillment) return { error: 'Escolha se você vai retirar ou receber em casa.' };
  const addr = {
    cep: onlyDigits(b?.cep, 8), street: cleanText(b?.street).slice(0, 100), number: cleanText(b?.number).slice(0, 10),
    complement: cleanText(b?.complement).slice(0, 60), district: cleanText(b?.district).slice(0, 60), city: cleanText(b?.city).slice(0, 60),
  };
  if (fulfillment === 'entrega') {
    if (addr.cep.length !== 8) return { error: 'Informe o CEP com 8 números.' };
    if (!addr.street) return { error: 'Informe a rua do endereço de entrega.' };
    if (!addr.number) return { error: 'Informe o número (ou S/N, se não tiver).' };
    if (!addr.district) return { error: 'Informe o bairro.' };
    if (!addr.city) return { error: 'Informe a cidade.' };
  }

  const scheduled_for = isoDate(b?.scheduled_for);
  if (!scheduled_for) return { error: 'Escolha a data desejada.' };
  if (scheduled_for < addDays(today, LEAD_DAYS)) return { error: `Fazemos tudo sob encomenda: escolha uma data com pelo menos ${leadDaysLabel()}.` };
  if (scheduled_for > addDays(today, 180)) return { error: 'Escolha uma data nos próximos 6 meses.' };
  const time_window = pick(TIME_WINDOWS, cleanText(b?.time_window));
  if (!time_window) return { error: 'Escolha o período do dia.' };

  const payment_method = pick(PAYMENT_METHODS, cleanText(b?.payment_method));
  if (!payment_method) return { error: 'Escolha a forma de pagamento.' };
  let change_for: number | null = null;
  const cf = cleanText(b?.change_for);
  if (payment_method === 'dinheiro' && cf) {
    const n = parseLocaleNumber(cf);
    if (!Number.isFinite(n) || n < 0 || n > 100000) return { error: 'O valor do troco não é válido.' };
    if (n < total) return { error: 'O troco precisa ser para um valor maior ou igual ao total do pedido.' };
    change_for = Math.round(n * 100) / 100;
  }

  return {
    value: {
      contact_name, phone, fulfillment, ...(fulfillment === 'entrega' ? addr : { cep: '', street: '', number: '', complement: '', district: '', city: '' }),
      scheduled_for, time_window, payment_method, change_for, notes: cleanText(b?.notes).slice(0, 300), items, total,
    },
  };
}

export const isEmail = (v: string) => v.length <= 120 && /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(v) && !/\.invalid$/i.test(v); // .invalid é o domínio técnico do convidado do link de pagamento
/** Só aceita caminhos internos da área do cliente (evita redirecionar para outro site). */
export const safeNext = (v: unknown) => { const s = String(v ?? ''); return s.startsWith('/cliente/') && !s.startsWith('//') && !s.includes('\\') ? s : '/cliente/pedidos'; };

/** Gramatura de uma unidade para exibir no cardápio: "80 g", "1,5 kg". Vazio se não informada. */
export function weightLabel(g?: number): string {
  if (!g || g <= 0) return '';
  const n = (v: number) => String(Math.round(v * 100) / 100).replace('.', ',');
  return g >= 1000 ? `${n(g / 1000)} kg` : `${n(g)} g`;
}
