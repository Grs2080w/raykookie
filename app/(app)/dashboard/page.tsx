import { AlertTriangle, ArrowRight, CalendarClock, ChefHat, TrendingUp } from 'lucide-react';
import Link from 'next/link';
import { GoalRing } from '@/components/GoalRing';
import { Badge } from '@/components/ui/Badge';
import { Card } from '@/components/ui/form';
import { Counter } from '@/components/ui/Money';
import { requireUser } from '@/lib/auth';
import { dateBR, money, monthKey, number, percent } from '@/lib/format';
import { goalProgress, stockStatus } from '@/lib/pricing';
import { ORDER_STATUS } from '@/lib/order-labels';
import { getGoal, getIngredients, getMonthStats, getOrders, getPricedRecipes } from '@/lib/queries';

export const metadata = { title: 'Início' };

const MONTHS = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro'];

export default async function Page() {
  const user = await requireUser();
  const month = monthKey();
  const [goal, stats, ingredients, orders, recipes] = await Promise.all([
    getGoal(user.id, month), getMonthStats(user.id, month), getIngredients(user.id), getOrders(user.id), getPricedRecipes(user.id),
  ]);
  const g = goalProgress(goal.revenue, stats.revenue);
  const now = new Date();
  const low = ingredients.filter((i) => stockStatus(i.stock_qty, i.min_stock) !== 'ok').slice(0, 6);
  const upcoming = orders.filter((o) => !['entregue', 'cancelado'].includes(o.status)).sort((a, b) => (a.delivery_date ?? '9999').localeCompare(b.delivery_date ?? '9999')).slice(0, 5);
  const best = [...recipes].filter((r) => r.pricing.rounded > 0).sort((a, b) => b.pricing.realMarginPct - a.pricing.realMarginPct).slice(0, 5);
  const receivable = orders.filter((o) => o.status !== 'cancelado' && o.payment !== 'pago').reduce((s, o) => s + o.qty * o.unit_price, 0);
  const empty = ingredients.length === 0 && recipes.length === 0;

  return (
    <>
      <div className="mb-8">
        <h1 className="font-display text-[34px] font-semibold leading-tight md:text-[42px]">Olá, {user.username}</h1>
        <p className="mt-1 text-muted">{now.getDate()} de {MONTHS[now.getMonth()]}. Aqui está o resumo do seu mês.</p>
      </div>

      {empty && (
        <Card className="mb-6 flex flex-wrap items-center justify-between gap-4 border-pink/40 bg-pink-tint p-5">
          <div><p className="font-display text-xl font-semibold text-pink-strong">Comece pelo estoque</p><p className="text-sm text-pink-strong/85">Cadastre seus ingredientes com o preço da embalagem, depois monte uma receita para ver o preço de venda.</p></div>
          <Link href="/estoque" className="inline-flex items-center gap-2 rounded-xl bg-pink px-4 py-2.5 font-semibold text-on-pink hover:bg-pink-strong">Cadastrar ingredientes <ArrowRight size={17} /></Link>
        </Card>
      )}

      <div className="grid gap-6 lg:grid-cols-[1.15fr_1fr]">
        <Link href="/metas" className="group block rounded-3xl">
          <Card className="flex h-full flex-col gap-6 p-6 transition-colors group-hover:border-pink/60 sm:flex-row sm:items-center">
            <GoalRing pct={g.pct} size={170} />
            <div className="min-w-0">
              <p className="text-sm text-muted">Faturamento de {MONTHS[now.getMonth()]}</p>
              <p className="font-display text-4xl font-semibold text-pink-strong"><Counter value={stats.revenue} /></p>
              <p className="mt-1 text-sm text-muted">{goal.revenue > 0 ? `Meta de ${money(goal.revenue)}` : 'Você ainda não definiu a meta do mês'}</p>
              <dl className="mt-5 flex flex-wrap gap-x-8 gap-y-3 text-sm">
                <div><dt className="text-muted">Pedidos</dt><dd className="font-display text-2xl font-semibold"><Counter value={stats.orders} kind="int" /></dd></div>
                <div><dt className="text-muted">Ticket médio</dt><dd className="font-display text-2xl font-semibold"><Counter value={stats.avg_ticket} /></dd></div>
                <div><dt className="text-muted">A receber</dt><dd className="font-display text-2xl font-semibold"><Counter value={receivable} /></dd></div>
              </dl>
            </div>
          </Card>
        </Link>

        <Card className="p-6">
          <div className="flex items-center justify-between"><h2 className="flex items-center gap-2 font-display text-xl font-semibold"><CalendarClock size={20} className="text-pink" />Próximas entregas</h2><Link href="/pedidos" className="text-sm font-semibold text-pink-strong hover:underline">Ver pedidos</Link></div>
          {upcoming.length === 0 ? <p className="mt-6 text-sm text-muted">Nenhum pedido em andamento.</p> : (
            <ul className="mt-3 divide-y divide-line">
              {upcoming.map((o) => (
                <li key={o.id} className="flex items-center gap-3 py-2.5">
                  <span className="tnum w-14 shrink-0 text-sm font-semibold text-pink-strong">{o.delivery_date ? dateBR(o.delivery_date).slice(0, 5) : 'sem data'}</span>
                  <div className="min-w-0 flex-1"><p className="truncate font-medium">{o.customer}</p><p className="truncate text-sm text-muted">{number(o.qty)}× {o.product}</p></div>
                  <Badge tone={(ORDER_STATUS.find((s) => s.id === o.status)?.tone ?? 'neutral') as any}>{ORDER_STATUS.find((s) => s.id === o.status)?.label}</Badge>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card className="p-6">
          <div className="flex items-center justify-between"><h2 className="flex items-center gap-2 font-display text-xl font-semibold"><AlertTriangle size={20} className="text-warn" />Precisam de reposição</h2><Link href="/estoque" className="text-sm font-semibold text-pink-strong hover:underline">Ver estoque</Link></div>
          {low.length === 0 ? <p className="mt-6 text-sm text-muted">{ingredients.length ? 'Tudo em dia. Nenhum ingrediente abaixo do mínimo.' : 'Cadastre ingredientes para acompanhar o estoque.'}</p> : (
            <ul className="mt-3 divide-y divide-line">
              {low.map((i) => (
                <li key={i.id} className="flex items-center justify-between gap-3 py-2.5"><span className="truncate font-medium">{i.name}</span><Badge tone={i.stock_qty <= 0 ? 'bad' : 'warn'}>{i.stock_qty <= 0 ? 'Acabou' : `${number(i.stock_qty)} restante${i.stock_qty === 1 ? '' : 's'}`}</Badge></li>
              ))}
            </ul>
          )}
        </Card>

        <Card className="p-6">
          <div className="flex items-center justify-between"><h2 className="flex items-center gap-2 font-display text-xl font-semibold"><TrendingUp size={20} className="text-ok" />Receitas mais lucrativas</h2><Link href="/receitas" className="text-sm font-semibold text-pink-strong hover:underline">Ver receitas</Link></div>
          {best.length === 0 ? <p className="mt-6 flex items-center gap-2 text-sm text-muted"><ChefHat size={16} />Crie receitas para ver o ranking de margem.</p> : (
            <ol className="mt-3 divide-y divide-line">
              {best.map((r) => (
                <li key={r.id}><Link href={`/receitas/${r.id}`} className="flex items-center gap-3 py-2.5 hover:text-pink-strong"><span className="min-w-0 flex-1 truncate font-medium">{r.name}</span><span className="tnum text-sm text-muted">{money(r.pricing.rounded)}</span><span className="tnum w-14 text-right text-sm font-semibold text-ok">{percent(r.pricing.realMarginPct)}</span></Link></li>
              ))}
            </ol>
          )}
        </Card>
      </div>
    </>
  );
}
