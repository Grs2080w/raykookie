'use client';
import { ArrowDown, ArrowUp, BarChart3, Minus } from 'lucide-react';
import { useEffect, useState } from 'react';
import clsx from 'clsx';
import { EmptyState } from '@/components/PageHeader';
import { Card } from '@/components/ui/form';
import { api } from '@/lib/client';
import { money, number, percent } from '@/lib/format';
import {
  PLATFORM_LABELS, REFERRER_LABELS,
  type EngagementFunnel, type EngagementOverview, type EngagementPage, type EngagementRecipe, type EngagementRecipeSort, type EngagementSources, type EngagementSummary,
} from '@shared/engagement';

const PERIODS = [{ days: 7, label: '7 dias' }, { days: 30, label: '30 dias' }, { days: 90, label: '90 dias' }];
const PLATFORMS = [{ id: '', label: 'Tudo' }, { id: 'site', label: 'Site' }, { id: 'android', label: 'App Android' }, { id: 'ios', label: 'App iPhone' }];
const SORTS: { id: EngagementRecipeSort; label: string }[] = [
  { id: 'views', label: 'Vistas' }, { id: 'clicks', label: 'Cliques' }, { id: 'adds', label: 'Adicionados' }, { id: 'orders', label: 'Pedidos' }, { id: 'revenue', label: 'Receita' },
];

const day = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

type Data = {
  overview: EngagementOverview; funnel: EngagementFunnel; pages: EngagementPage[]; sources: EngagementSources;
};

/** Variação de um número contra o período anterior (sem cálculo de negócio: só a diferença para mostrar). */
function Delta({ now, before, invert = false }: { now: number; before: number; invert?: boolean }) {
  if (before === 0 && now === 0) return <span className="text-xs text-muted">sem mudança</span>;
  if (before === 0) return <span className="text-xs text-muted">novo no período</span>;
  const pct = ((now - before) / before) * 100;
  if (Math.abs(pct) < 0.5) return <span className="inline-flex items-center gap-1 text-xs text-muted"><Minus size={13} />igual ao período anterior</span>;
  const good = invert ? pct < 0 : pct > 0;
  return (
    <span className={clsx('inline-flex items-center gap-1 text-xs font-semibold', good ? 'text-ok' : 'text-bad')}>
      {pct > 0 ? <ArrowUp size={13} /> : <ArrowDown size={13} />}{percent(Math.abs(pct))} vs. período anterior
    </span>
  );
}

function Stat({ label, value, now, before }: { label: string; value: string; now: number; before: number }) {
  return (
    <Card className="p-5">
      <p className="text-sm text-muted">{label}</p>
      <p className="mt-1 font-display text-3xl font-semibold tnum">{value}</p>
      <div className="mt-1.5"><Delta now={now} before={before} /></div>
    </Card>
  );
}

function Bar({ pct, tone = 'bg-pink' }: { pct: number; tone?: string }) {
  return <div className="h-2.5 overflow-hidden rounded-full bg-surface-2"><div className={clsx('h-full rounded-full', tone)} style={{ width: `${Math.max(0, Math.min(100, pct))}%` }} /></div>;
}

export function EngagementView() {
  const [days, setDays] = useState(30);
  const [platform, setPlatform] = useState('');
  const [sort, setSort] = useState<EngagementRecipeSort>('clicks');
  const [data, setData] = useState<Data | null>(null);
  const [recipes, setRecipes] = useState<EngagementRecipe[] | null>(null);
  const [error, setError] = useState('');

  const query = (extra = '') => {
    const to = new Date();
    const from = new Date(); from.setDate(to.getDate() - (days - 1));
    return `?from=${day(from)}&to=${day(to)}${platform ? `&platform=${platform}` : ''}${extra}`;
  };

  useEffect(() => {
    let alive = true;
    setError('');
    Promise.all([
      api<EngagementOverview>(`/api/engagement/overview${query()}`, 'GET'),
      api<EngagementFunnel>(`/api/engagement/funnel${query()}`, 'GET'),
      api<{ list: EngagementPage[] }>(`/api/engagement/pages${query()}`, 'GET'),
      api<EngagementSources>(`/api/engagement/sources${query()}`, 'GET'),
    ]).then(([overview, funnel, pages, sources]) => { if (alive) setData({ overview, funnel, pages: pages.list, sources }); })
      .catch((e) => { if (alive) setError(e.message); });
    return () => { alive = false; };
  // eslint-disable-next-line react-hooks/exhaustive-deps -- query() só lê days/platform
  }, [days, platform]);

  useEffect(() => {
    let alive = true;
    api<{ list: EngagementRecipe[] }>(`/api/engagement/recipes${query(`&sort=${sort}`)}`, 'GET')
      .then((r) => { if (alive) setRecipes(r.list); }).catch(() => { if (alive) setRecipes([]); });
    return () => { alive = false; };
  // eslint-disable-next-line react-hooks/exhaustive-deps -- query() só lê days/platform
  }, [days, platform, sort]);

  const cur: EngagementSummary | undefined = data?.overview.current;
  const prev: EngagementSummary | undefined = data?.overview.previous;

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
        <div role="group" aria-label="Período" className="flex gap-1.5">
          {PERIODS.map((p) => <button key={p.days} type="button" aria-pressed={days === p.days} onClick={() => setDays(p.days)} className={clsx('min-h-10 rounded-full border px-4 text-sm font-semibold', days === p.days ? 'border-pink bg-pink-tint text-pink-strong' : 'border-line bg-surface hover:border-pink')}>Últimos {p.label}</button>)}
        </div>
        <div role="group" aria-label="Onde" className="flex flex-wrap gap-1.5">
          {PLATFORMS.map((p) => <button key={p.id} type="button" aria-pressed={platform === p.id} onClick={() => setPlatform(p.id)} className={clsx('min-h-10 rounded-full border px-4 text-sm font-semibold', platform === p.id ? 'border-pink bg-pink-tint text-pink-strong' : 'border-line bg-surface hover:border-pink')}>{p.label}</button>)}
        </div>
      </div>

      {error && <p role="alert" className="rounded-2xl border border-bad/30 bg-bad/10 p-4 text-sm text-bad">{error}</p>}
      {!data && !error && <p className="text-muted" role="status">Carregando…</p>}

      {data && cur && prev && cur.sessions === 0 && cur.orders === 0 && (
        <EmptyState icon={<BarChart3 size={26} />} title="Ainda sem dados neste período" text="Quando os clientes abrirem o site ou o app, os números aparecem aqui. Os pedidos e a receita já vêm direto dos seus pedidos." />
      )}

      {data && cur && prev && (cur.sessions > 0 || cur.orders > 0) && (
        <>
          <section aria-label="Resumo" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <Stat label="Visitas (sessões)" value={number(cur.sessions)} now={cur.sessions} before={prev.sessions} />
            <Stat label="Páginas e telas vistas" value={number(cur.page_views)} now={cur.page_views} before={prev.page_views} />
            <Stat label="Pedidos" value={number(cur.orders)} now={cur.orders} before={prev.orders} />
            <Stat label="Conversão (visita → pedido)" value={percent(cur.conversion_pct)} now={cur.conversion_pct} before={prev.conversion_pct} />
          </section>
          <p className="-mt-4 text-sm text-muted">Pedidos pagos no período: {number(cur.paid_orders)} · Receita: {money(cur.revenue)}. Cada visita é uma sessão anônima: a mesma pessoa em dias diferentes conta mais de uma vez.</p>

          <section aria-labelledby="funil">
            <h2 id="funil" className="font-display text-2xl font-semibold">Até onde as pessoas vão no pedido</h2>
            {data.funnel.biggest_drop && <p className="mt-1 text-sm text-muted">Onde mais param: depois de <strong className="text-ink">{data.funnel.biggest_drop.after}</strong> ({number(data.funnel.biggest_drop.lost)} {data.funnel.biggest_drop.lost === 1 ? 'visita não seguiu' : 'visitas não seguiram'}).</p>}
            <Card className="mt-3 flex flex-col gap-4 p-5">
              {data.funnel.steps.map((s, i) => (
                <div key={s.key}>
                  <div className="flex flex-wrap items-baseline justify-between gap-x-4 text-sm">
                    <p className="font-semibold">{i + 1}. {s.label}</p>
                    <p className="tnum"><strong>{number(s.sessions)}</strong>{i > 0 && <span className="text-muted"> · {percent(s.pct_of_top)} do total · {percent(s.pct_of_previous)} do passo anterior</span>}</p>
                  </div>
                  <div className="mt-1.5"><Bar pct={s.pct_of_top} /></div>
                </div>
              ))}
            </Card>
          </section>

          <section aria-labelledby="receitas">
            <h2 id="receitas" className="font-display text-2xl font-semibold">Receitas e combos que mais chamam atenção</h2>
            <p className="mt-1 text-sm text-muted">Vista = apareceu na tela; clique = tocou em Pedir; adicionado = entrou no pedido. Pedidos e receita vêm dos pedidos feitos no período.</p>
            <Card className="mt-3 overflow-x-auto">
              <table className="w-full min-w-[640px] text-sm">
                <thead>
                  <tr className="border-b border-line text-left text-muted">
                    <th className="px-4 py-3 font-semibold">Item</th>
                    {SORTS.map((c) => (
                      <th key={c.id} className="px-3 py-3 text-right font-semibold" aria-sort={sort === c.id ? 'descending' : 'none'}>
                        <button type="button" onClick={() => setSort(c.id)} className={clsx('inline-flex min-h-9 items-center gap-1 rounded-lg px-1.5', sort === c.id ? 'text-pink-strong' : 'hover:text-ink')}>{c.label}{sort === c.id && <ArrowDown size={13} />}</button>
                      </th>
                    ))}
                    <th className="px-3 py-3 text-right font-semibold">Clique → pedido</th>
                  </tr>
                </thead>
                <tbody>
                  {recipes === null && <tr><td colSpan={7} className="px-4 py-6 text-muted">Carregando…</td></tr>}
                  {recipes?.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-muted">Nenhum item visto ou pedido neste período.</td></tr>}
                  {recipes?.map((r) => (
                    <tr key={r.item_id} className="border-b border-line/60 last:border-0">
                      <td className="px-4 py-3 font-semibold">{r.name}</td>
                      <td className="tnum px-3 py-3 text-right">{number(r.views)}</td>
                      <td className="tnum px-3 py-3 text-right">{number(r.clicks)} <span className="text-xs text-muted">({percent(r.click_rate_pct)})</span></td>
                      <td className="tnum px-3 py-3 text-right">{number(r.adds)}</td>
                      <td className="tnum px-3 py-3 text-right">{number(r.orders)}</td>
                      <td className="tnum px-3 py-3 text-right">{money(r.revenue)}</td>
                      <td className="tnum px-3 py-3 text-right">{percent(r.order_rate_pct)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          </section>

          <div className="grid gap-8 lg:grid-cols-2">
            <section aria-labelledby="paginas">
              <h2 id="paginas" className="font-display text-2xl font-semibold">Páginas e telas mais vistas</h2>
              <Card className="mt-3 p-5">
                {data.pages.length === 0 ? <p className="text-sm text-muted">Nada visto neste período.</p> : (
                  <ul className="flex flex-col gap-3.5">
                    {data.pages.slice(0, 12).map((p) => (
                      <li key={p.route}>
                        <div className="flex items-baseline justify-between gap-3 text-sm"><code className="truncate font-semibold">{p.route}</code><span className="tnum shrink-0">{number(p.views)} <span className="text-muted">vistas · {number(p.sessions)} visitas</span></span></div>
                        <div className="mt-1"><Bar pct={(p.views / data.pages[0].views) * 100} tone="bg-pink/70" /></div>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
            </section>

            <section aria-labelledby="origens">
              <h2 id="origens" className="font-display text-2xl font-semibold">De onde vêm as visitas</h2>
              <Card className="mt-3 flex flex-col gap-5 p-5">
                <div>
                  <p className="mb-2 text-sm font-semibold text-muted">Origem da entrada (site)</p>
                  {data.sources.referrers.length === 0 ? <p className="text-sm text-muted">Sem dados de origem.</p> : (
                    <ul className="flex flex-col gap-3">
                      {data.sources.referrers.map((r) => (
                        <li key={r.kind}>
                          <div className="flex justify-between gap-3 text-sm"><span className="font-semibold">{REFERRER_LABELS[r.kind] ?? r.kind}</span><span className="tnum">{number(r.sessions)}</span></div>
                          <div className="mt-1"><Bar pct={(r.sessions / data.sources.referrers[0].sessions) * 100} tone="bg-pink/70" /></div>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
                <div>
                  <p className="mb-2 text-sm font-semibold text-muted">Onde acessam</p>
                  <ul className="flex flex-col gap-1.5 text-sm">
                    {data.sources.platforms.length === 0 && <li className="text-muted">Sem dados.</li>}
                    {data.sources.platforms.map((p) => <li key={p.platform} className="flex justify-between gap-3"><span className="font-semibold">{PLATFORM_LABELS[p.platform] ?? p.platform}</span><span className="tnum">{number(p.sessions)} visitas</span></li>)}
                  </ul>
                </div>
              </Card>
            </section>
          </div>
        </>
      )}
    </div>
  );
}
