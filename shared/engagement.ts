// Catálogo da medição de engajamento (site e apps dos clientes). É a fonte única dos nomes e dos valores aceitos: o
// servidor (EngagementCatalog.java) e o app (mobile-common) são comparados com o JSON gerado daqui
// (infra/rules-vectors/gen-engagement.mjs). A medição é anônima: só `session_id` aleatório de uma visita, sem conta,
// IP, código fixo por aparelho nem conteúdo de campos.

/** O que cada evento leva. `route`/`item`/`qty`: o evento usa (e exige) o campo. `details`: valores aceitos em `detail`. */
export type EventRule = { route: boolean; item: boolean; qty: boolean; detailRequired: boolean; details: readonly string[] };

export const REFERRERS = ['direct', 'instagram', 'whatsapp', 'search', 'other'] as const;
export const SURFACES = ['landing', 'cardapio', 'home_app'] as const;
export const CHECKOUT_STEPS = ['items', 'data', 'delivery', 'payment', 'extras'] as const;
export const PAY_METHODS = ['pix', 'cartao', 'dinheiro'] as const;
export const COUPON_RESULTS = ['ok', 'invalid', 'min_total', 'inactive', 'used_up'] as const;
export const SUBMIT_RESULTS = ['ok', 'validation', 'card_refused', 'network', 'other'] as const;
export const THREE_DS_RESULTS = ['authenticated', 'closed', 'failed'] as const;

const none = { route: false, item: false, qty: false, detailRequired: false, details: [] } as const;

export const ENGAGEMENT_EVENTS = {
  screen_view: { ...none, route: true, details: REFERRERS },
  menu_item_view: { ...none, item: true, detailRequired: true, details: SURFACES },
  menu_item_click: { ...none, item: true, detailRequired: true, details: SURFACES },
  cart_add: { ...none, item: true, qty: true },
  cart_remove: { ...none, item: true, qty: true },
  checkout_step: { ...none, detailRequired: true, details: CHECKOUT_STEPS },
  payment_method_select: { ...none, detailRequired: true, details: PAY_METHODS },
  coupon_attempt: { ...none, detailRequired: true, details: COUPON_RESULTS },
  checkout_submit: { ...none, detailRequired: true, details: SUBMIT_RESULTS },
  card_3ds: { ...none, detailRequired: true, details: THREE_DS_RESULTS },
  order_placed: { ...none, detailRequired: true, details: PAY_METHODS },
} as const satisfies Record<string, EventRule>;

export type EngagementName = keyof typeof ENGAGEMENT_EVENTS;

/** Campos de um evento. `item_id` = id da receita ou `combo-<id>` (o mesmo `id` do cardápio e do pedido). */
export type EngagementProps = { route?: string; item_id?: string; detail?: string; qty?: number };

/** Evento como vai no lote (`at` em milissegundos desde 1970). */
export type EngagementEvent = EngagementProps & { name: EngagementName; at: number };

/** Origem da entrada a partir do `document.referrer`. Só a categoria sai do aparelho, nunca o endereço. */
export function referrerKind(referrer: string): (typeof REFERRERS)[number] {
  if (!referrer) return 'direct';
  let host = '';
  try { host = new URL(referrer).hostname.toLowerCase(); } catch { return 'other'; }
  if (/(^|\.)(instagram\.com|l\.instagram\.com)$/.test(host)) return 'instagram';
  if (/(^|\.)(whatsapp\.com|wa\.me)$/.test(host)) return 'whatsapp';
  if (/(^|\.)(google|bing|duckduckgo|yahoo|ecosia)\./.test(host + '.')) return 'search';
  return 'other';
}

/** Motivo do cupom recusado, pela mensagem do servidor (o código digitado nunca é enviado). */
export function couponReason(message: string): (typeof COUPON_RESULTS)[number] {
  const text = message.toLowerCase();
  if (/m[ií]nim|a partir de|acima de/.test(text)) return 'min_total';
  if (/inativ|desativ/.test(text)) return 'inactive';
  if (/esgot|já foi usado|ja foi usado|limite/.test(text)) return 'used_up';
  return 'invalid';
}

// ── Respostas de leitura da dona (GET /api/engagement/*); a gestão web e o app de gestão só mostram, nada é calculado nelas.

export type EngagementSummary = { sessions: number; page_views: number; orders: number; paid_orders: number; revenue: number; conversion_pct: number };
export type EngagementOverview = { from: string; to: string; platform: string | null; current: EngagementSummary; previous: EngagementSummary };
export type EngagementFunnelStep = { key: string; label: string; sessions: number; pct_of_top: number; pct_of_previous: number };
export type EngagementFunnel = { from: string; to: string; steps: EngagementFunnelStep[]; biggest_drop: { after: string; lost: number } | null };
export type EngagementRecipe = { item_id: string; name: string; views: number; clicks: number; adds: number; orders: number; revenue: number; click_rate_pct: number; order_rate_pct: number };
export type EngagementRecipeSort = 'views' | 'clicks' | 'adds' | 'orders' | 'revenue';
export type EngagementPage = { route: string; views: number; sessions: number };
export type EngagementSources = { referrers: { kind: string; sessions: number }[]; platforms: { platform: string; sessions: number }[] };

/** Nome de cada origem de entrada e de cada plataforma, para as telas. */
export const REFERRER_LABELS: Record<string, string> = { direct: 'Direto (digitou ou salvou o endereço)', instagram: 'Instagram', whatsapp: 'WhatsApp', search: 'Busca (Google e outros)', other: 'Outros sites' };
export const PLATFORM_LABELS: Record<string, string> = { site: 'Site', android: 'App Android', ios: 'App iPhone' };
