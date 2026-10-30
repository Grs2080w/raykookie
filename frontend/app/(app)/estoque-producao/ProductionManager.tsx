'use client';
import { AlertTriangle, Boxes, ChevronDown, Factory, Pencil, Search, Undo2 } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useEffect, useMemo, useState } from 'react';
import clsx from 'clsx';
import { EmptyState } from '@/components/PageHeader';
import { Badge } from '@/components/ui/Badge';
import { Button, Card, Field, Input, NumberInput, Select } from '@/components/ui/form';
import { ConfirmDialog, Drawer } from '@/components/ui/Modal';
import { Counter } from '@/components/ui/Money';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { money, moneyPrecise, number, parseNum, percent } from '@/lib/format';
import type { ProductionPreview, ProductionRow, ProductionRun, ProductionSummary } from '@shared/types';

/** Abaixo (ou igual) disto o estoque de produção de uma receita aparece como "baixo". */
const LOW_UNITS = 3;
const SORTS = { name: 'Nome', qty: 'Quantidade', profit: 'Lucro do estoque', margin: 'Margem' } as const;
type SortKey = keyof typeof SORTS;
const stockProfit = (r: ProductionRow) => r.qty * (r.profit_unit ?? 0);
const when = (iso: string) => new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' });
const qtyText = (n: number) => number(n);

export function ProductionManager({ recipes, summary, runs }: { recipes: ProductionRow[]; summary: ProductionSummary; runs: ProductionRun[] }) {
  const router = useRouter();
  const [tab, setTab] = useState<'estoque' | 'historico'>('estoque');
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<SortKey>('name');
  const [produce, setProduce] = useState<{ recipe: string } | null>(null);
  const [adjust, setAdjust] = useState<{ row: ProductionRow; qty: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const rows = useMemo(() => {
    const list = recipes.filter((r) => !search || r.name.toLowerCase().includes(search.toLowerCase()));
    const by: Record<SortKey, (a: ProductionRow, b: ProductionRow) => number> = {
      name: (a, b) => a.name.localeCompare(b.name, 'pt-BR'),
      qty: (a, b) => b.qty - a.qty,
      profit: (a, b) => stockProfit(b) - stockProfit(a),
      margin: (a, b) => (b.margin_pct ?? -Infinity) - (a.margin_pct ?? -Infinity),
    };
    return [...list].sort(by[sort]);
  }, [recipes, search, sort]);

  async function saveAdjust() {
    if (!adjust) return;
    setBusy(true);
    try { await api(`/api/production/stock/${adjust.row.recipe_id}`, 'PATCH', { qty: adjust.qty }); toast.ok('Estoque de produção atualizado'); setAdjust(null); router.refresh(); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }

  return (
    <>
      <div className="mb-6 grid grid-cols-2 gap-3 md:grid-cols-4">
        <Card className="p-4"><p className="text-sm text-muted">Unidades em estoque</p><p className="font-display mt-1 text-3xl font-semibold"><Counter value={summary.units} kind="int" /></p></Card>
        <Card className="p-4"><p className="text-sm text-muted">Custo do estoque</p><p className="font-display mt-1 text-3xl font-semibold"><Counter value={summary.cost} /></p></Card>
        <Card className="p-4"><p className="text-sm text-muted">Receita se vender tudo</p><p className="font-display mt-1 text-3xl font-semibold"><Counter value={summary.revenue} /></p></Card>
        <Card className="p-4"><p className="text-sm text-muted">Lucro estimado</p><p className="font-display mt-1 text-3xl font-semibold text-ok"><Counter value={summary.profit} /></p></Card>
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="inline-flex rounded-full border border-line bg-surface p-1">
          {([['estoque', 'Estoque'], ['historico', 'Histórico de produção']] as const).map(([id, label]) => (
            <button key={id} onClick={() => setTab(id)} className={clsx('rounded-full px-4 py-1.5 text-[13px] font-semibold transition-colors', tab === id ? 'bg-pink text-on-pink' : 'text-muted hover:text-ink')}>{label}</button>
          ))}
        </div>
        <div className="ml-auto"><Button onClick={() => setProduce({ recipe: '' })}><Factory size={18} />Registrar produção</Button></div>
      </div>

      {tab === 'estoque' ? (
        <>
          <div className="mb-4 flex flex-wrap items-center gap-3">
            <div className="relative min-w-56 flex-1">
              <Search size={17} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" />
              <Input aria-label="Buscar receita" placeholder="Buscar receita" value={search} onChange={(e) => setSearch(e.target.value)} className="pl-10" />
            </div>
            <div className="w-52"><Select aria-label="Ordenar por" value={sort} onChange={(e) => setSort(e.target.value as SortKey)}>{Object.entries(SORTS).map(([k, v]) => <option key={k} value={k}>Ordenar: {v}</option>)}</Select></div>
          </div>
          {recipes.length === 0 ? (
            <EmptyState icon={<Boxes size={26} />} title="Nenhuma receita ainda" text="Cadastre receitas para controlar quantas unidades prontas você tem." />
          ) : rows.length === 0 ? (
            <p className="rounded-3xl border border-dashed border-line px-6 py-12 text-center text-muted">Nenhuma receita encontrada.</p>
          ) : (
            <Card className="overflow-hidden">
              <ul>
                {rows.map((r) => <StockLine key={r.recipe_id} r={r} onProduce={() => setProduce({ recipe: String(r.recipe_id) })} onAdjust={() => setAdjust({ row: r, qty: String(r.qty).replace('.', ',') })} />)}
              </ul>
            </Card>
          )}
        </>
      ) : <History recipes={recipes} initial={runs} />}

      <ProduceDrawer open={!!produce} initialRecipe={produce?.recipe ?? ''} recipes={recipes} onClose={() => setProduce(null)} onDone={() => { setProduce(null); router.refresh(); }} />

      <Drawer open={!!adjust} onClose={() => setAdjust(null)} title="Ajustar estoque de produção"
        footer={<><Button variant="ghost" onClick={() => setAdjust(null)}>Cancelar</Button><Button type="submit" form="adjust-form" busy={busy}>Salvar</Button></>}>
        {adjust && (
          <form id="adjust-form" className="grid gap-4" onSubmit={(e) => { e.preventDefault(); saveAdjust(); }}>
            <p className="text-sm text-muted">Use para corrigir a contagem quando vender ou perder unidades fora do sistema. Isso não mexe nos ingredientes.</p>
            <Field label={`Unidades prontas de ${adjust.row.name}`}>{(id) => <NumberInput id={id} value={adjust.qty} onValueChange={(v) => setAdjust({ ...adjust, qty: v })} />}</Field>
          </form>
        )}
      </Drawer>
    </>
  );
}

/** Uma receita no estoque de produção. */
function StockLine({ r, onProduce, onAdjust }: { r: ProductionRow; onProduce: () => void; onAdjust: () => void }) {
  const empty = r.qty <= 0, low = !empty && r.qty <= LOW_UNITS;
  const dash = <span className="text-muted">—</span>;
  return (
    <li className={clsx('grid grid-cols-2 items-center gap-x-4 gap-y-2 border-b border-line px-5 py-4 last:border-0 lg:grid-cols-[minmax(0,1.8fr)_0.8fr_0.9fr_0.9fr_1fr_1fr_1fr_auto]', empty && 'bg-bad/5')}>
      <div className="col-span-2 min-w-0 lg:col-span-1">
        <p className="truncate font-semibold">{r.name}</p>
        <div className="mt-1 flex flex-wrap items-center gap-2">
          {empty ? <Badge tone="bad">Sem estoque</Badge> : low ? <Badge tone="warn">Estoque baixo</Badge> : null}
          {r.can_make !== null && <span className={clsx('text-xs', r.can_make === 0 ? 'text-bad' : 'text-muted')}>{r.can_make === 0 ? `Faltam ingredientes${r.blocking.length ? `: ${r.blocking.join(', ')}` : ''}` : `Dá para produzir ${qtyText(r.can_make)} un`}</span>}
        </div>
      </div>
      <Cell label="Em estoque"><span className={clsx('font-semibold', empty && 'text-bad', low && 'text-warn')}>{qtyText(r.qty)} un</span></Cell>
      <Cell label="Custo unitário">{r.unit_cost === null ? dash : moneyPrecise(r.unit_cost)}</Cell>
      <Cell label="Preço de venda">{r.price === null ? dash : money(r.price)}</Cell>
      <Cell label="Lucro / margem">{r.profit_unit === null ? dash : <>{money(r.profit_unit)} <span className="text-muted">· {percent(r.margin_pct ?? 0)}</span></>}</Cell>
      <Cell label="Lucro do estoque">{r.profit_unit === null ? dash : money(stockProfit(r))}</Cell>
      <Cell label="Investido">{r.unit_cost === null ? dash : money(r.qty * r.unit_cost)}</Cell>
      <div className="col-span-2 flex justify-end gap-1 lg:col-span-1">
        <Button variant="soft" size="sm" onClick={onProduce}><Factory size={15} />Produzir</Button>
        <Button variant="ghost" size="sm" aria-label={`Ajustar estoque de ${r.name}`} onClick={onAdjust}><Pencil size={16} /></Button>
      </div>
    </li>
  );
}

function Cell({ label, children }: { label: string; children: React.ReactNode }) {
  return <div className="tnum text-sm"><span className="mb-0.5 block text-xs text-muted lg:hidden">{label}</span>{children}</div>;
}

/** Registrar produção em duas etapas: receita e quantidade, depois a conferência dos ingredientes que serão descontados. */
function ProduceDrawer({ open, initialRecipe, recipes, onClose, onDone }: { open: boolean; initialRecipe: string; recipes: ProductionRow[]; onClose: () => void; onDone: () => void }) {
  const [recipe, setRecipe] = useState('');
  const [qty, setQty] = useState('');
  const [review, setReview] = useState<ProductionPreview | null>(null);
  const [force, setForce] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (open) { setRecipe(initialRecipe); setQty(''); setReview(null); setForce(false); } }, [open, initialRecipe]);

  async function next() {
    const n = parseNum(qty);
    if (!recipe) return toast.error('Escolha a receita.');
    if (!Number.isInteger(n) || n <= 0) return toast.error('Informe quantas unidades foram produzidas (número inteiro maior que zero).');
    setBusy(true);
    try { setReview(await api<ProductionPreview>('/api/production/preview', 'POST', { recipe_id: Number(recipe), qty: n })); setForce(false); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function confirm() {
    if (!review || busy) return; // trava o duplo clique
    setBusy(true);
    try { await api('/api/production/runs', 'POST', { recipe_id: review.recipe_id, qty: review.qty, force }); toast.ok('Produção registrada'); onDone(); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }

  return (
    <Drawer open={open} onClose={onClose} title={review ? 'Confirmar produção' : 'Registrar produção'}
      footer={review
        ? <><Button variant="ghost" onClick={() => setReview(null)} disabled={busy}>Voltar</Button><Button onClick={confirm} busy={busy} disabled={review.short && !force}>Confirmar produção</Button></>
        : <><Button variant="ghost" onClick={onClose}>Cancelar</Button><Button onClick={next} busy={busy}>Revisar</Button></>}>
      {!review ? (
        <form className="grid gap-4" onSubmit={(e) => { e.preventDefault(); next(); }}>
          <Field label="Receita">{(id) => (
            <Select id={id} value={recipe} onChange={(e) => setRecipe(e.target.value)}>
              <option value="">Escolha a receita</option>{recipes.map((r) => <option key={r.recipe_id} value={r.recipe_id}>{r.name}</option>)}
            </Select>)}</Field>
          <Field label="Unidades produzidas" hint="Ex.: 24. Os ingredientes são calculados pelo rendimento da receita.">{(id) => <NumberInput id={id} integer value={qty} onValueChange={setQty} />}</Field>
        </form>
      ) : (
        <div className="grid gap-4">
          <p className="rounded-2xl bg-pink-tint px-4 py-3 text-sm text-pink-strong"><b>{qtyText(review.qty)} × {review.recipe}</b><br />Custo dos ingredientes: <b>{money(review.total_cost)}</b></p>
          {review.short && (
            <div className="rounded-2xl border border-bad/40 bg-bad/10 px-4 py-3 text-sm text-bad" role="alert">
              <p className="flex items-center gap-2 font-bold"><AlertTriangle size={16} />Estoque de ingredientes insuficiente</p>
              <ul className="mt-1 list-disc pl-5">{review.lines.filter((l) => l.missing > 0).map((l) => <li key={l.ingredient_id}>{l.name}: faltam {qtyText(l.missing)} {l.unit}</li>)}</ul>
              <label className="mt-3 flex cursor-pointer items-start gap-2 font-semibold"><input type="checkbox" className="mt-1" checked={force} onChange={(e) => setForce(e.target.checked)} />Produzir mesmo assim (meu estoque está desatualizado). O estoque do ingrediente para em zero.</label>
            </div>
          )}
          {review.lines.length === 0 ? <p className="text-sm text-muted">Esta receita não tem ingredientes.</p> : (
            <ul className="divide-y divide-line rounded-2xl border border-line">
              {review.lines.map((l) => (
                <li key={l.ingredient_id} className="px-4 py-2.5 text-sm">
                  <div className="flex justify-between gap-3"><span className="font-semibold">{l.name}</span><span className="tnum">−{qtyText(l.needed)} {l.unit}</span></div>
                  <div className={clsx('tnum flex justify-between gap-3 text-xs', l.missing > 0 ? 'text-bad' : 'text-muted')}><span>Estoque: {qtyText(l.stock)} → {qtyText(l.after)} {l.unit}</span><span>{money(l.cost)}</span></div>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </Drawer>
  );
}

/** Histórico de produção com filtro por receita e período, e "Desfazer". */
function History({ recipes, initial }: { recipes: ProductionRow[]; initial: ProductionRun[] }) {
  const router = useRouter();
  const [runs, setRuns] = useState(initial);
  const [recipe, setRecipe] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [open, setOpen] = useState<number | null>(null);
  const [undo, setUndo] = useState<ProductionRun | null>(null);
  const [busy, setBusy] = useState(false);

  const query = () => `/api/production/runs?${new URLSearchParams({ ...(recipe && { recipe }), ...(from && { from }), ...(to && { to }) })}`;
  async function load() { try { setRuns(await api<ProductionRun[]>(query(), 'GET')); } catch (e: any) { toast.error(e.message); } }
  useEffect(() => { load(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [recipe, from, to, initial]);

  async function doUndo() {
    if (!undo) return;
    setBusy(true);
    try { await api(`/api/production/runs/${undo.id}/undo`, 'POST'); toast.ok('Produção desfeita'); setUndo(null); router.refresh(); }
    catch (e: any) { toast.error(e.message); setUndo(null); } finally { setBusy(false); }
  }

  return (
    <>
      <div className="mb-4 grid gap-3 md:grid-cols-3">
        <Select aria-label="Filtrar por receita" value={recipe} onChange={(e) => setRecipe(e.target.value)}><option value="">Todas as receitas</option>{recipes.map((r) => <option key={r.recipe_id} value={r.recipe_id}>{r.name}</option>)}</Select>
        <Input aria-label="De" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        <Input aria-label="Até" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
      </div>
      {runs.length === 0 ? <p className="rounded-3xl border border-dashed border-line px-6 py-12 text-center text-muted">Nenhuma produção registrada.</p> : (
        <Card className="overflow-hidden">
          <ul>
            {runs.map((r) => (
              <li key={r.id} className={clsx('border-b border-line px-5 py-4 last:border-0', r.undone_at && 'opacity-60')}>
                <div className="flex flex-wrap items-center gap-3">
                  <div className="min-w-0 flex-1">
                    <p className="font-semibold">{qtyText(r.qty)} × {r.recipe}</p>
                    <p className="text-xs text-muted">{when(r.created_at)} · custo {money(r.total_cost)}</p>
                  </div>
                  {r.undone_at ? <Badge tone="neutral">Desfeita</Badge> : r.forced ? <Badge tone="warn">Com falta de ingrediente</Badge> : null}
                  <Button variant="ghost" size="sm" aria-label="Ver ingredientes" onClick={() => setOpen(open === r.id ? null : r.id)}><ChevronDown size={16} className={clsx(open === r.id && 'rotate-180')} /></Button>
                  {!r.undone_at && <Button variant="soft" size="sm" onClick={() => setUndo(r)}><Undo2 size={15} />Desfazer</Button>}
                </div>
                {open === r.id && (
                  <ul className="mt-3 grid gap-1 rounded-2xl bg-surface-2 px-4 py-3 text-sm">
                    {r.consumed.map((c) => <li key={c.ingredient_id} className="tnum flex justify-between gap-3"><span>{c.name}</span><span className="text-muted">{qtyText(c.needed)} {c.unit} · {money(c.cost)}</span></li>)}
                  </ul>
                )}
              </li>
            ))}
          </ul>
        </Card>
      )}
      <ConfirmDialog open={!!undo} busy={busy} confirmLabel="Desfazer produção" title={`Desfazer ${undo ? qtyText(undo.qty) : ''} × ${undo?.recipe ?? ''}?`}
        message="Os ingredientes voltam para o estoque e as unidades saem do estoque de produção. Se parte delas já saiu do estoque, o sistema bloqueia." onClose={() => setUndo(null)} onConfirm={doUndo} />
    </>
  );
}
