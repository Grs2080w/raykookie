import { stockStatus } from '@shared/pricing';
import type { Dashboard } from '@shared/types';
import { monthKeyBR } from './dates';
import { q1 } from './db';
import { getGoal, getIngredients, getMonthStats, getOrders, getPricedRecipes } from './queries';

// Resumo da tela inicial do painel: mesmas regras que o web usava direto no servidor de páginas.
export async function getDashboard(userId: number, username: string): Promise<Dashboard> {
  const month = monthKeyBR();
  const [goal, stats, ingredients, orders, recipes] = await Promise.all([
    getGoal(userId, month), getMonthStats(userId, month), getIngredients(userId), getOrders(userId), getPricedRecipes(userId),
  ]);
  const low = ingredients.filter((i) => stockStatus(i.stock_qty, i.min_stock) !== 'ok').slice(0, 6);
  const upcoming = orders.filter((o) => !['entregue', 'cancelado'].includes(o.status))
    .sort((a, b) => (a.delivery_date ?? '9999').localeCompare(b.delivery_date ?? '9999')).slice(0, 5);
  const best = [...recipes].filter((r) => r.pricing.rounded > 0).sort((a, b) => b.pricing.realMarginPct - a.pricing.realMarginPct).slice(0, 5);
  const receivable = orders.filter((o) => o.status !== 'cancelado' && o.payment !== 'pago').reduce((s, o) => s + o.qty * o.unit_price, 0);
  const rf = await q1<{ waiting: number; pending: number; failed: number }>(
    `SELECT count(*) FILTER (WHERE status = 'solicitado')::int AS waiting, COALESCE(sum(approved_amount) FILTER (WHERE status IN ('aprovado','processando','falhou')),0)::float8 AS pending,
            count(*) FILTER (WHERE status = 'falhou')::int AS failed FROM refunds WHERE owner_id = $1`, [userId]);
  return { refundsWaiting: rf?.waiting ?? 0, refundsPending: rf?.pending ?? 0, refundsFailed: rf?.failed ?? 0, username, month, ingredientCount: ingredients.length, goal, stats, receivable, empty: ingredients.length === 0 && recipes.length === 0, low, upcoming, best };
}
