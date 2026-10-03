'use client';
import { motion } from 'motion/react';
import { ChevronLeft, ChevronRight, ClipboardList, DollarSign, Receipt, UserPlus } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Badge } from '@/components/ui/Badge';
import { Button, Card, Field, NumberInput } from '@/components/ui/form';
import { Counter } from '@/components/ui/Money';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { money, number, parseNum, fieldNum } from '@/lib/format';
import { goalProgress } from '@shared/pricing';
import type { Goal, MonthStats } from '@shared/types';

const MONTHS = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro'];

function statusOf(target: number, pct: number) {
  if (target <= 0) return { tone: 'neutral' as const, label: 'Sem meta definida' };
  if (pct >= 100) return { tone: 'ok' as const, label: 'Meta batida' };
  if (pct >= 80) return { tone: 'warn' as const, label: 'Quase lá' };
  return { tone: 'bad' as const, label: 'Abaixo da meta' };
}

export function GoalsBoard({ month, goal, stats }: { month: string; goal: Goal; stats: MonthStats }) {
  const router = useRouter();
  const [f, setF] = useState({ revenue: fieldNum(goal.revenue || ''), orders: fieldNum(goal.orders || ''), new_customers: fieldNum(goal.new_customers || ''), avg_ticket: fieldNum(goal.avg_ticket || '') });
  const [busy, setBusy] = useState(false);
  const [y, m] = month.split('-').map(Number);
  const shift = (d: number) => { const dt = new Date(y, m - 1 + d, 1); router.push(`/metas?m=${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}`); };

  // usa os valores digitados, então o painel reage antes mesmo de salvar
  const targets = { revenue: parseNum(f.revenue), orders: parseNum(f.orders), new_customers: parseNum(f.new_customers), avg_ticket: parseNum(f.avg_ticket) };
  const items = [
    { key: 'revenue', label: 'Faturamento', icon: DollarSign, target: targets.revenue, actual: stats.revenue, fmt: money },
    { key: 'orders', label: 'Pedidos', icon: ClipboardList, target: targets.orders, actual: stats.orders, fmt: number },
    { key: 'avg_ticket', label: 'Ticket médio', icon: Receipt, target: targets.avg_ticket, actual: stats.avg_ticket, fmt: money },
    { key: 'new_customers', label: 'Clientes novos', icon: UserPlus, target: targets.new_customers, actual: stats.new_customers, fmt: number },
  ] as const;
  const main = goalProgress(targets.revenue, stats.revenue);

  async function save() {
    setBusy(true);
    try { await api('/api/goals', 'PUT', { month, ...f }); toast.ok('Metas salvas'); router.refresh(); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }

  const R = 70, C = 2 * Math.PI * R;
  return (
    <>
      <div className="mb-6 flex items-center gap-2">
        <Button variant="ghost" size="sm" onClick={() => shift(-1)} aria-label="Mês anterior"><ChevronLeft size={18} /></Button>
        <h2 className="font-display min-w-44 text-center text-2xl font-semibold">{MONTHS[m - 1][0].toUpperCase() + MONTHS[m - 1].slice(1)} de {y}</h2>
        <Button variant="ghost" size="sm" onClick={() => shift(1)} aria-label="Próximo mês"><ChevronRight size={18} /></Button>
      </div>

      <div className="grid gap-6 lg:grid-cols-[340px_minmax(0,1fr)]">
        <Card className="flex flex-col items-center p-6 text-center">
          <div className="relative size-52">
            <svg viewBox="0 0 180 180" className="size-full -rotate-90" role="img" aria-label={`${Math.round(main.pct)}% da meta de faturamento`}>
              <circle cx="90" cy="90" r={R} fill="none" stroke="var(--surface-2)" strokeWidth="14" />
              <motion.circle cx="90" cy="90" r={R} fill="none" stroke="var(--pink)" strokeWidth="14" strokeLinecap="round" strokeDasharray={C}
                initial={{ strokeDashoffset: C }} animate={{ strokeDashoffset: C * (1 - Math.min(main.pct, 100) / 100) }} transition={{ duration: 1.1, ease: [0.22, 1, 0.36, 1] }} />
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
              <span className="font-display text-5xl font-semibold"><Counter value={main.pct} kind="int" />%</span>
              <span className="text-sm text-muted">da meta</span>
            </div>
          </div>
          <p className="mt-4 text-sm text-muted">Faturado</p>
          <p className="font-display text-3xl font-semibold text-pink-strong"><Counter value={stats.revenue} /></p>
          <p className="mt-3 text-sm text-muted">Lucro total</p>
          <p className="font-display text-2xl font-semibold text-pink-strong"><Counter value={stats.profit} /></p>
          <p className="mt-1 text-sm text-muted">{targets.revenue > 0 ? (main.diff >= 0 ? `${money(main.diff)} acima da meta` : `Faltam ${money(-main.diff)}`) : 'Defina a meta de faturamento ao lado'}</p>
        </Card>

        <div className="space-y-6">
          <Card className="p-5">
            <h3 className="font-display text-xl font-semibold">Suas metas do mês</h3>
            <form className="mt-4 grid grid-cols-2 gap-4 md:grid-cols-4" onSubmit={(e) => { e.preventDefault(); save(); }}>
              <Field label="Faturamento (R$)">{(id) => <NumberInput id={id} value={f.revenue} onValueChange={(v) => setF({ ...f, revenue: v })} />}</Field>
              <Field label="Pedidos">{(id) => <NumberInput integer id={id} value={f.orders} onValueChange={(v) => setF({ ...f, orders: v })} />}</Field>
              <Field label="Ticket médio (R$)">{(id) => <NumberInput id={id} value={f.avg_ticket} onValueChange={(v) => setF({ ...f, avg_ticket: v })} />}</Field>
              <Field label="Clientes novos">{(id) => <NumberInput integer id={id} value={f.new_customers} onValueChange={(v) => setF({ ...f, new_customers: v })} />}</Field>
              <div className="col-span-2 md:col-span-4"><Button type="submit" busy={busy}>Salvar metas</Button></div>
            </form>
          </Card>

          <div className="grid gap-4 sm:grid-cols-2">
            {items.map(({ key, label, icon: Icon, target, actual, fmt }) => {
              const g = goalProgress(target, actual);
              const st = statusOf(target, g.pct);
              return (
                <Card key={key} className="p-5">
                  <div className="flex items-center justify-between">
                    <span className="flex items-center gap-2 font-semibold"><span className="grid size-9 place-items-center rounded-xl bg-pink-tint text-pink-strong"><Icon size={18} /></span>{label}</span>
                    <Badge tone={st.tone}>{st.label}</Badge>
                  </div>
                  <p className="mt-4 flex items-baseline gap-2"><span className="font-display text-3xl font-semibold tnum">{fmt(actual)}</span><span className="text-sm text-muted">de {target > 0 ? fmt(target) : '—'}</span></p>
                  <div className="mt-3 h-2.5 overflow-hidden rounded-full bg-surface-2">
                    <motion.div className="h-full rounded-full bg-pink" initial={{ width: 0 }} animate={{ width: `${Math.min(g.pct, 100)}%` }} transition={{ duration: 0.9, ease: [0.22, 1, 0.36, 1] }} />
                  </div>
                  <p className="mt-2 text-xs text-muted tnum">{target > 0 ? `${Math.round(g.pct)}% atingido · diferença ${g.diff >= 0 ? '+' : '−'}${fmt(Math.abs(g.diff))}` : 'Sem meta definida'}</p>
                </Card>
              );
            })}
          </div>
        </div>
      </div>
    </>
  );
}
