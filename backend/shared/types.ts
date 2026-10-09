// Formatos de dados que o backend devolve e o frontend/mobile consomem (contrato da API).
import type { PricingResult } from './pricing';

export type CatKind = 'ingredient' | 'recipe' | 'expense';
export type Category = { id: number; kind: CatKind; name: string; icon: string; color: string; uses: number };
export type Ingredient = {
  id: number; category_id: number | null; name: string; unit: 'g' | 'ml' | 'un';
  stock_qty: number; package_size: number; package_cost: number; min_stock: number; supplier: string; notes: string;
};
export type Settings = { hourly_rate: number; monthly_units: number; min_margin_pct: number }; // min_margin_pct: abaixo disso o painel avisa que a margem caiu
export type FixedCost = { id: number; category_id: number | null; name: string; kind: 'fixed' | 'variable'; amount: number };
export type Order = {
  id: number; number: number; order_date: string; customer: string; recipe_id: number | null; product: string;
  qty: number; unit_price: number; delivery_date: string | null; status: string; payment: string; notes: string;
  address: string; // endereço de entrega (ou "Retirada no local") dos pedidos online; vazio nos pedidos manuais
  shop_order_id?: number | null; shop_code?: number | null; // pedido online: id interno e número que o cliente vê
  shop_paid?: boolean; shop_total?: number | null; shop_refunded?: number | null; // pedido online: pago? total e quanto já foi devolvido
  shop_payment_method?: string | null; shop_installments?: number | null; // pedido online: pix, cartao (com quantas parcelas) ou dinheiro
  shop_cancel?: { by: string; code?: string | null; reason: string; note: string } | null; // pedido online cancelado: por quem e por quê
  shop_refund?: { id: number; kind: string; status: string; reason_code: string; requested_amount: number; approved_amount: number | null; fail_reason?: string | null; decision_code?: string | null; photos: number } | null; // a devolução mais recente do pedido
};
export type RecipeRow = {
  id: number; name: string; category_id: number | null; yield_units: number; margin_pct: number; prep_hours: number; notes: string;
  unit_weight_g: number; // gramas de cada unidade que a receita rende; 0 = não informado
  sale_price: number | null; // preço do cardápio fixado pela confeiteira; nulo = o sugerido pelo cálculo (pricing.rounded já considera este valor)
  photo_v: string | null; // versão da foto (muda a cada envio); nulo = sem foto
  show_on_menu: boolean; // aparece no cardápio da loja (editor de cardápio do painel)
  tags: string[]; // características fixas (ver shared/menu-tags.ts) exibidas no cardápio
  items: { ingredient_id: number; qty: number }[];
};
export type PricedRecipe = RecipeRow & { pricing: PricingResult };
export type Goal = { revenue: number; orders: number; new_customers: number; avg_ticket: number };
export type MonthStats = { revenue: number; profit: number; orders: number; avg_ticket: number; new_customers: number };

export type User = { id: number; username: string };
export type Customer = {
  id: number; email: string; name: string; phone: string;
  cep: string; street: string; number: string; complement: string; district: string; city: string;
};

export type Dashboard = {
  username: string; month: string; ingredientCount: number; goal: Goal; stats: MonthStats; receivable: number; empty: boolean;
  low: Ingredient[]; upcoming: Order[]; best: PricedRecipe[];
  refundsWaiting: number; refundsPending: number; refundsFailed: number; // devoluções: aguardando decisão, valor em processamento, as que falharam
};

export type MenuItem = { id: string; name: string; price: number; category: string; image?: string; weight_g?: number; tags?: string[] };
export type MenuGroup = { id: string; name: string; items: MenuItem[] };
export type Menu = { items: MenuItem[]; groups: MenuGroup[] };

export type RefundInfo = { mode: 'automatico' | 'solicitacao' | 'nenhum'; max_amount: number; photos_required: boolean; deadline: string | null; why_not: string };
export type CustomerRefund = {
  id: number; kind: 'automatico' | 'solicitado' | 'loja' | 'sistema'; status: 'solicitado' | 'desistiu' | 'negado' | 'aprovado' | 'processando' | 'concluido'; // 'falhou' aparece como 'processando'
  label: string; reason: string; requested_amount: number; approved_amount: number | null; decision_note: string; decision_reason: string; photos: number; created_at: string;
  refunded_at: string | null; // quando o Pix voltou (concluída)
};

export type ShopOrder = {
  id: number; code: number; // code: o número que o cliente vê (4 ou 5 dígitos, aleatório); id é interno
  created_at: string; scheduled_for: string; time_window: string; fulfillment: 'retirada' | 'entrega';
  pickup_address: string; // endereço de retirada da loja na época do pedido (vazio se não for retirada ou se não havia um escolhido)
  street: string; number: string; complement: string; district: string; city: string; payment_method: string;
  installments?: number; // só relevante para payment_method 'cartao' (1 = à vista)
  notes: string; total: number; items: { id: string; name: string; qty: number; unit_price: number }[]; status: string;
  paid: boolean; // o webhook do Mercado Pago confirmou o pagamento
  manual: boolean; // pedido registrado à mão pago pelo link (sem horário nem endereço)
  can_cancel: boolean; // o cliente ainda pode cancelar por conta própria: a produção não começou (se já pagou, o Pix volta na hora: ver refund.mode)
  payment_state: 'pendente' | 'expirado' | 'cancelado' | 'pago' | 'devolucao_parcial' | 'devolvido';
  refunded_total: number; // valor já devolvido (Pix)
  cancelled_by: 'cliente' | 'loja' | 'sistema' | null; cancel_reason: string; // quem cancelou e o motivo que o cliente lê
  cancel: { by: 'cliente' | 'loja' | 'sistema'; code: string; reason: string; note: string; at: string } | null; // cancelamento: quem, código e motivo (da lista), mensagem livre e quando
  refund: RefundInfo; // o que o cliente pode fazer agora sobre devolução (calculado no servidor)
  refunds: CustomerRefund[]; // devoluções e solicitações deste pedido, mais nova primeiro
  pix?: { code: string; qr_base64: string; expires_in: number; expired: boolean } | null; // Pix copia e cola e validade (segundos restantes), enquanto aguardando pagamento; vencido vem sem código
};

/** Versão publicada de um dos apps (manifesto em downloads/manifest.json). */
export type AppId = 'client' | 'server';
export type AppRelease = {
  app: AppId; name: string; version: string; build: number; file: string; size: number; sha256: string; date: string;
  url?: string; // preenchido pela API: endereço de download
};
export type AppReleases = Partial<Record<AppId, AppRelease>>;

/** Página pública do link de pagamento de pedido registrado à mão (`GET /api/pagar/:token`). */
export type PayLinkView = {
  state: 'aberto' | 'pago' | 'cancelado';
  amount: number;
  order: { number: number; customer: string; product: string; qty: number; unit_price: number; delivery_date: string | null };
  pix: { code: string; qr_base64: string; expires_in: number; expired: boolean } | null;
  shop_order: ShopOrder | null;
};
