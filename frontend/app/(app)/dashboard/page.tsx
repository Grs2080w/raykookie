import { AlertTriangle, ArrowRight, CalendarClock, ChefHat, TrendingUp } from 'lucide-react';
import Link from 'next/link';
import { GoalRing } from '@/components/GoalRing';
import { Badge } from '@/components/ui/Badge';
import { Card } from '@/components/ui/form';
import { Counter } from '@/components/ui/Money';
import { apiGet } from '@/lib/server-api';
import type { Dashboard } from '@shared/types';
import { dateBR, money, number, percent } from '@/lib/format';
import { PriceAlerts, type PriceAlert } from '@/components/PriceAlerts';
import { goalProgress } from '@shared/pricing';
import { ORDER_STATUS } from '@shared/order-labels';

export const metadata = { title: 'Início' };

const MONTHS = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro'];

export default async function Page() {
  const d = await apiGet<Dashboard>('/api/dashboard');
  const alerts = await apiGet<PriceAlert[]>('/api/price-alerts');
  const { goal, stats, low, upcoming, best, receivable, empty } = d;
  const user = { username: d.username };
  const g = goalProgress(goal.revenue, stats.revenue);
  const now = new Date();

  return (
    <>
      <div className="mb-8">
        <h1 className="font-display text-[34px] font-semibold leading-tight md:text-[42px]">Olá, {user.username}</h1>
        <p className="mt-1 text-muted">{now.getDate()} de {MONTHS[now.getMonth()]}. Aqui está o resumo do seu mês.</p>
      </div>

      <PriceAlerts alerts={alerts} />

      {(d.refundsWaiting > 0 || d.refundsFailed > 0 || d.refundsPending > 0) && (
        <Link href="/devolucoes" className="mb-6 block rounded-2xl border border-warn/40 bg-warn/10 p-4 hover:border-warn">
          <p className="flex items-center gap-2 font-semibold"><AlertTriangle size={18} className="text-warn" />
            {d.refundsWaiting > 0 ? `${d.refundsWaiting} devolução${d.refundsWaiting > 1 ? 's' : ''} aguardando sua decisão` : d.refundsFailed > 0 ? 'Devolução parada' : 'Devoluções em processamento'}
          </p>
          <p className="mt-1 text-sm text-muted">
            {d.refundsFailed > 0 ? 'Uma devolução não saiu (provavelmente falta saldo no Mercado Pago). ' : ''}
            {d.refundsPending > 0 ? `Mantenha ${money(d.refundsPending)} disponíveis no Mercado Pago para as devoluções em processamento.` : 'Toque para ver.'}
          </p>
        </Link>
      )}

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
              <p className="mt-2 text-sm text-muted">Lucro total</p>
              <p className="font-display text-2xl font-semibold text-pink-strong"><Counter value={stats.profit} /></p>
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
          {low.length === 0 ? <p className="mt-6 text-sm text-muted">{d.ingredientCount ? 'Tudo em dia. Nenhum ingrediente abaixo do mínimo.' : 'Cadastre ingredientes para acompanhar o estoque.'}</p> : (
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
