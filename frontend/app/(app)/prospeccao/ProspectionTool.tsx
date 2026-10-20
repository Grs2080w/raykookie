'use client';
import clsx from 'clsx';
import { Copy, Download, FolderOpen, Plus, Save, Search, Table2, Trash2, TriangleAlert, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Badge } from '@/components/ui/Badge';
import { ConfirmDialog } from '@/components/ui/Modal';
import { toast } from '@/components/ui/Toast';
import { Button, Card, Field, Input, NumberInput, Select } from '@/components/ui/form';
import { TZ, todayBR } from '@/lib/dates';
import { money, number, parseNum, percent2 } from '@/lib/format';
import { emptyDraft, loadDraft, loadSimulations, saveDraft, saveSimulations, uid, type Draft, type Simulation } from '@/lib/prospection-store';
import { calcProspect, csvFileName, newItem, toCsv, type DiscountMode, type Level, type ProspectItem, type ProspectRow, type Tier } from '@shared/prospection';
import { FinalTable } from './FinalTable';

export type CatalogProduct = { id: number; name: string; category: string | null; cost: number; price: number };

/** Texto de um número para o campo: 12.5 → "12,5"; vazio quando é null. */
const asText = (v: number | null) => (v === null ? '' : String(v).replace('.', ','));

/** Campo numérico com texto próprio (digita "1,", "0,0" sem o valor pular). Vazio vale 0, ou null quando `nullable`. */
function Num({ value, onChange, nullable, required, integer, label, suffix, placeholder }: {
  value: number | null; onChange: (v: number | null) => void; nullable?: boolean; required?: boolean; integer?: boolean; label: string; suffix?: string; placeholder?: string;
}) {
  const [text, setText] = useState(asText(value));
  useEffect(() => { if (parseNum(text) !== (value ?? 0) || (value === null && text !== '')) setText(asText(value)); }, [value]); // eslint-disable-line react-hooks/exhaustive-deps
  const blank = text.trim() === '';
  return (
    <Field label={label} hint={required && blank ? 'Obrigatório' : undefined}>
      {(id) => (
        <div className="relative">
          <NumberInput id={id} integer={integer} value={text} placeholder={placeholder} onFocus={(e) => e.currentTarget.select()} aria-invalid={required && blank} className={clsx(suffix && 'pr-9', required && blank && '!border-bad')}
            onValueChange={(s) => { setText(s); onChange(s.trim() === '' ? (nullable ? null : 0) : parseNum(s)); }} />
          {suffix && <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted">{suffix}</span>}
        </div>
      )}
    </Field>
  );
}

const levelTone = { ok: 'ok', low: 'warn', negative: 'bad' } as const;
const levelText = (l: Level, m: number) => (l === 'negative' ? `Prejuízo · ${percent2(m)}` : l === 'low' ? `Abaixo do mínimo · ${percent2(m)}` : `Margem ${percent2(m)}`);

export function ProspectionTool({ products, categories }: { products: CatalogProduct[]; categories: string[] }) {
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [sims, setSims] = useState<Simulation[]>([]);
  const [ready, setReady] = useState(false);
  const [q, setQ] = useState('');
  const [cat, setCat] = useState('');
  const [manual, setManual] = useState({ name: '', cost: '', price: '' });
  const [view, setView] = useState<'edit' | 'table'>('edit');
  const [generated, setGenerated] = useState('');
  const [del, setDel] = useState<Simulation | null>(null);
  const { items, globals } = draft;

  // O rascunho volta do navegador ao abrir a aba e é regravado a cada mudança: trocar de aba não perde nada.
  useEffect(() => { setDraft(loadDraft()); setSims(loadSimulations()); setReady(true); }, []);
  useEffect(() => { if (ready) saveDraft(draft); }, [draft, ready]);

  const { rows, totals } = useMemo(() => calcProspect(items, globals), [items, globals]);
  const rowOf = useMemo(() => new Map(rows.map((r) => [r.id, r])), [rows]);
  const picked = useMemo(() => new Set(items.map((i) => i.id)), [items]);

  const patch = (p: Partial<Draft>) => setDraft((d) => ({ ...d, ...p }));
  const setGlobals = (g: Partial<typeof globals>) => setDraft((d) => ({ ...d, globals: { ...d.globals, ...g } }));
  const setItem = (id: string, p: Partial<ProspectItem>) => setDraft((d) => ({ ...d, items: d.items.map((i) => (i.id === id ? { ...i, ...p } : i)) }));
  const fromCatalog = (p: CatalogProduct) => newItem({ id: `r-${p.id}`, name: p.name, cost: p.cost, price: p.price });

  const filtered = useMemo(() => {
    const t = q.trim().toLowerCase();
    return products.filter((p) => (!cat || p.category === cat) && (!t || p.name.toLowerCase().includes(t)));
  }, [products, q, cat]);

  const toggle = (p: CatalogProduct) => setDraft((d) => {
    const id = `r-${p.id}`;
    return { ...d, items: d.items.some((i) => i.id === id) ? d.items.filter((i) => i.id !== id) : [...d.items, fromCatalog(p)] };
  });
  const selectAll = () => setDraft((d) => ({ ...d, items: [...d.items, ...filtered.filter((p) => !d.items.some((i) => i.id === `r-${p.id}`)).map(fromCatalog)] }));
  const clearSelection = () => setDraft((d) => ({ ...d, items: [] }));

  function addManual() {
    const name = manual.name.trim();
    if (!name) return toast.error('Dê um nome ao item.');
    const cost = parseNum(manual.cost), price = parseNum(manual.price);
    if (manual.cost.trim() === '' || manual.price.trim() === '') return toast.error('Informe o custo e o preço de venda.');
    if (cost < 0 || price < 0) return toast.error('Custo e preço não podem ser negativos.');
    setDraft((d) => ({ ...d, items: [...d.items, newItem({ id: `m-${uid()}`, name, cost, price })] }));
    setManual({ name: '', cost: '', price: '' });
  }

  // ── simulações salvas
  const persist = (l: Simulation[]) => { setSims(l); saveSimulations(l); };
  function save(asCopy = false) {
    const name = draft.name.trim();
    if (!name) return toast.error('Dê um nome à simulação para salvar.');
    const now = new Date().toISOString();
    const snap = { name: asCopy ? `${name} (cópia)` : name, updatedAt: now, items, globals };
    if (!asCopy && draft.savedId && sims.some((s) => s.id === draft.savedId)) {
      persist(sims.map((s) => (s.id === draft.savedId ? { ...s, ...snap } : s)).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)));
      toast.ok('Simulação atualizada.');
    } else {
      const id = uid();
      persist([{ id, ...snap }, ...sims]);
      patch({ savedId: id, name: snap.name });
      toast.ok(asCopy ? 'Cópia salva.' : 'Simulação salva.');
    }
  }
  const open = (s: Simulation) => { setDraft({ items: s.items, globals: s.globals, name: s.name, savedId: s.id }); toast.ok(`“${s.name}” aberta.`); };
  const duplicate = (s: Simulation) => {
    const copy = { ...s, id: uid(), name: `${s.name} (cópia)`, updatedAt: new Date().toISOString() };
    persist([copy, ...sims]);
    toast.ok('Simulação duplicada.');
  };

  // ── saída
  const csv = () => {
    if (!items.length) return toast.error('Escolha ao menos um produto.');
    const url = URL.createObjectURL(new Blob([toCsv(rows, totals)], { type: 'text/csv;charset=utf-8' }));
    const a = Object.assign(document.createElement('a'), { href: url, download: csvFileName(todayBR()) });
    document.body.append(a); a.click(); a.remove(); URL.revokeObjectURL(url);
  };
  const openTable = () => {
    if (!items.length) return toast.error('Escolha ao menos um produto.');
    setGenerated(new Date().toLocaleString('pt-BR', { timeZone: TZ, dateStyle: 'short', timeStyle: 'short' }));
    setView('table');
  };

  if (view === 'table') return <FinalTable rows={rows} totals={totals} title={draft.name.trim() || 'Prospecção de preços'} date={generated} onBack={() => setView('edit')} onCsv={csv} />;

  const setTier = (i: number, t: Partial<Tier>) => setGlobals({ tiers: globals.tiers.map((x, k) => (k === i ? { ...x, ...t } : x)) });

  return (
    <div className="space-y-6">
      {/* 1. parâmetros gerais */}
      <Card className="p-5">
        <h2 className="font-display text-xl font-semibold">Parâmetros gerais</h2>
        <p className="mt-0.5 text-sm text-muted">Valem para todos os itens. Cada item pode sobrescrever o desconto e o imposto.</p>
        <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <div className="flex gap-2">
            <Field label="Desconto geral" className="w-28 shrink-0">{(id) => (
              <Select id={id} value={globals.discountMode} onChange={(e) => setGlobals({ discountMode: e.target.value as DiscountMode })}><option value="pct">%</option><option value="brl">R$</option></Select>)}</Field>
            <div className="min-w-0 flex-1"><Num label=" " value={globals.discount} onChange={(v) => setGlobals({ discount: v ?? 0 })} suffix={globals.discountMode === 'pct' ? '%' : 'R$'} /></div>
          </div>
          <Num label="Impostos / taxas (sobre o bruto)" value={globals.taxPct} onChange={(v) => setGlobals({ taxPct: v ?? 0 })} suffix="%" />
          <Num label="Frete total (rateado)" value={globals.freight} onChange={(v) => setGlobals({ freight: v ?? 0 })} suffix="R$" />
          <Num label="Markup alvo (sobre o custo)" value={globals.targetMarkupPct} onChange={(v) => setGlobals({ targetMarkupPct: v ?? 0 })} suffix="%" />
          <Num label="Margem mínima (alerta)" value={globals.minMarginPct} onChange={(v) => setGlobals({ minMarginPct: v ?? 0 })} suffix="%" />
        </div>
        <div className="mt-5 border-t border-dashed border-line pt-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div><h3 className="text-sm font-semibold">Desconto progressivo por quantidade</h3><p className="text-xs text-muted">Ex.: 10+ un = 5%, 50+ un = 10%. Vale para os itens em que você marcar “usar faixas”.</p></div>
            <Button variant="soft" size="sm" onClick={() => setGlobals({ tiers: [...globals.tiers, { minQty: 0, pct: 0 }] })}><Plus size={15} />Faixa</Button>
          </div>
          {globals.tiers.length > 0 && (
            <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {globals.tiers.map((t, i) => (
                <div key={i} className="flex items-end gap-2">
                  <Num label="A partir de" value={t.minQty} onChange={(v) => setTier(i, { minQty: v ?? 0 })} suffix="un" />
                  <Num label="Desconto" value={t.pct} onChange={(v) => setTier(i, { pct: v ?? 0 })} suffix="%" />
                  <button aria-label="Remover faixa" onClick={() => setGlobals({ tiers: globals.tiers.filter((_, k) => k !== i) })} className="mb-1 grid size-10 shrink-0 place-items-center rounded-lg text-muted hover:bg-bad/10 hover:text-bad"><X size={17} /></button>
                </div>
              ))}
            </div>
          )}
        </div>
      </Card>

      {/* 2. escolha dos produtos */}
      <Card className="p-5">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div><h2 className="font-display text-xl font-semibold">Produtos</h2><p className="text-sm text-muted">Marque os produtos da simulação. Mudar custo ou preço aqui não altera o cadastro.</p></div>
          <p className="text-sm font-semibold text-pink-strong">{items.length} selecionado{items.length === 1 ? '' : 's'}</p>
        </div>
        <div className="mt-4 flex flex-wrap gap-2">
          <div className="relative min-w-52 flex-1">
            <Search size={16} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
            <Input aria-label="Buscar produto" placeholder="Buscar produto" value={q} onChange={(e) => setQ(e.target.value)} className="pl-9" />
          </div>
          {categories.length > 0 && (
            <Select aria-label="Filtrar por categoria" value={cat} onChange={(e) => setCat(e.target.value)} className="w-auto"><option value="">Todas as categorias</option>{categories.map((c) => <option key={c}>{c}</option>)}</Select>
          )}
          <Button variant="soft" onClick={selectAll} disabled={!filtered.length}>Selecionar todos{q || cat ? ' (filtrados)' : ''}</Button>
          <Button variant="ghost" onClick={clearSelection} disabled={!items.length}>Limpar seleção</Button>
        </div>
        {products.length === 0 ? <p className="mt-4 rounded-2xl border border-dashed border-line p-5 text-center text-sm text-muted">Nenhuma receita cadastrada ainda. Use o item manual abaixo.</p> : (
          <ul className="mt-3 grid max-h-72 gap-1 overflow-auto rounded-2xl border border-line p-1.5 sm:grid-cols-2">
            {filtered.map((p) => (
              <li key={p.id}>
                <label className={clsx('flex cursor-pointer items-center gap-3 rounded-xl px-3 py-2.5 hover:bg-surface-2', picked.has(`r-${p.id}`) && 'bg-pink-tint/60')}>
                  <input type="checkbox" className="size-4 accent-[var(--pink)]" checked={picked.has(`r-${p.id}`)} onChange={() => toggle(p)} />
                  <span className="min-w-0 flex-1"><span className="block truncate text-sm font-medium">{p.name}</span><span className="block text-xs text-muted">Custo {money(p.cost)} · Preço {p.price > 0 ? money(p.price) : 'sem preço'}</span></span>
                </label>
              </li>
            ))}
            {filtered.length === 0 && <li className="col-span-full p-4 text-center text-sm text-muted">Nada encontrado.</li>}
          </ul>
        )}
        <div className="mt-4 rounded-2xl bg-surface-2 p-4">
          <h3 className="text-sm font-semibold">Item manual (fora do cadastro)</h3>
          <div className="mt-2 grid gap-3 sm:grid-cols-[1fr_9rem_9rem_auto] sm:items-end">
            <Field label="Nome">{(id) => <Input id={id} value={manual.name} maxLength={80} onChange={(e) => setManual({ ...manual, name: e.target.value })} />}</Field>
            <Field label="Custo unit. (R$)">{(id) => <NumberInput id={id} value={manual.cost} onValueChange={(s) => setManual({ ...manual, cost: s })} />}</Field>
            <Field label="Preço de venda (R$)">{(id) => <NumberInput id={id} value={manual.price} onValueChange={(s) => setManual({ ...manual, price: s })} />}</Field>
            <Button onClick={addManual}><Plus size={17} />Adicionar</Button>
          </div>
        </div>
      </Card>

      {/* 3. itens */}
      {items.length > 0 && (
        <section aria-label="Itens da simulação" className="space-y-3">
          <h2 className="font-display text-xl font-semibold">Itens da simulação</h2>
          {items.map((it) => <ItemCard key={it.id} item={it} row={rowOf.get(it.id)} hasTiers={globals.tiers.length > 0} onChange={(p) => setItem(it.id, p)} onRemove={() => patch({ items: items.filter((x) => x.id !== it.id) })} />)}
        </section>
      )}

      {/* 4. totais e saída */}
      <Card className={clsx('p-5', totals.level !== 'ok' && items.length > 0 && 'border-2', totals.level === 'negative' && items.length > 0 && 'border-bad', totals.level === 'low' && items.length > 0 && 'border-warn')}>
        <h2 className="font-display text-xl font-semibold">Totais</h2>
        {items.length > 0 && totals.level !== 'ok' && <p role="alert" className={clsx('mt-2 flex items-center gap-2 text-sm font-semibold', totals.level === 'negative' ? 'text-bad' : 'text-warn')}><TriangleAlert size={17} />{totals.level === 'negative' ? 'A simulação dá prejuízo.' : `A margem total está abaixo do mínimo de ${percent2(globals.minMarginPct)}.`}</p>}
        <dl className="mt-4 grid grid-cols-2 gap-4 md:grid-cols-4">
          <Stat k="Total bruto" v={money(totals.gross)} />
          <Stat k="Total de desconto" v={money(totals.discountValue)} sub={percent2(totals.discountPct)} />
          <Stat k="Total líquido" v={money(totals.net)} strong />
          <Stat k="Custo total" v={money(totals.costTotal + totals.extra + totals.freight)} sub={totals.tax > 0 ? `+ impostos ${money(totals.tax)}` : undefined} />
          <Stat k="Lucro" v={money(totals.profit)} strong tone={items.length ? levelTone[totals.level] : undefined} />
          <Stat k="Margem (sobre a venda)" v={percent2(totals.marginPct)} tone={items.length ? levelTone[totals.level] : undefined} />
          <Stat k="Markup (sobre o custo)" v={percent2(totals.markupPct)} />
          <Stat k="Quantidade" v={number(totals.qty)} />
        </dl>
        <div className="mt-5 flex flex-wrap gap-2">
          <Button onClick={openTable}><Table2 size={17} />Gerar tabela final</Button>
          <Button variant="soft" onClick={csv}><Download size={17} />Exportar CSV</Button>
        </div>
      </Card>

      {/* 5. simulações salvas */}
      <Card className="p-5">
        <h2 className="font-display text-xl font-semibold">Simulações salvas</h2>
        <p className="text-sm text-muted">Ficam guardadas neste navegador (não sincronizam com o celular).</p>
        <div className="mt-4 flex flex-wrap items-end gap-2">
          <Field label="Nome da simulação" className="min-w-52 flex-1">{(id) => <Input id={id} value={draft.name} maxLength={60} placeholder="Ex.: Festa da Ana, 100 docinhos" onChange={(e) => patch({ name: e.target.value })} />}</Field>
          <Button onClick={() => save()}><Save size={17} />{draft.savedId ? 'Atualizar' : 'Salvar'}</Button>
          {draft.savedId && <Button variant="soft" onClick={() => save(true)}><Copy size={17} />Salvar como cópia</Button>}
          <Button variant="ghost" onClick={() => setDraft(emptyDraft())}>Nova simulação</Button>
        </div>
        {sims.length > 0 && (
          <ul className="mt-4 divide-y divide-line rounded-2xl border border-line">
            {sims.map((s) => (
              <li key={s.id} className={clsx('flex flex-wrap items-center justify-between gap-2 px-4 py-3', draft.savedId === s.id && 'bg-pink-tint/50')}>
                <div className="min-w-0"><p className="truncate font-medium">{s.name}</p><p className="text-xs text-muted">{s.items.length} ite{s.items.length === 1 ? 'm' : 'ns'} · {s.updatedAt ? new Date(s.updatedAt).toLocaleString('pt-BR', { timeZone: TZ, dateStyle: 'short', timeStyle: 'short' }) : ''}</p></div>
                <div className="flex gap-1">
                  <Button variant="soft" size="sm" onClick={() => open(s)}><FolderOpen size={15} />Abrir</Button>
                  <Button variant="ghost" size="sm" onClick={() => duplicate(s)}><Copy size={15} />Duplicar</Button>
                  <Button variant="danger" size="sm" aria-label={`Excluir ${s.name}`} onClick={() => setDel(s)}><Trash2 size={15} /></Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <ConfirmDialog open={!!del} title="Excluir simulação?" message={`“${del?.name ?? ''}” será apagada deste navegador.`}
        onClose={() => setDel(null)} onConfirm={() => { if (del) { persist(sims.filter((s) => s.id !== del.id)); if (draft.savedId === del.id) patch({ savedId: null }); } setDel(null); }} />
    </div>
  );
}

function Stat({ k, v, sub, strong, tone }: { k: string; v: string; sub?: string; strong?: boolean; tone?: 'ok' | 'warn' | 'bad' }) {
  return (
    <div>
      <dt className="text-xs text-muted">{k}</dt>
      <dd className={clsx('tnum font-display', strong ? 'text-2xl font-semibold' : 'text-xl', tone === 'ok' && 'text-ok', tone === 'warn' && 'text-warn', tone === 'bad' && 'text-bad')}>{v}</dd>
      {sub && <p className="tnum text-xs text-muted">{sub}</p>}
    </div>
  );
}

function ItemCard({ item, row, hasTiers, onChange, onRemove }: { item: ProspectItem; row?: ProspectRow; hasTiers: boolean; onChange: (p: Partial<ProspectItem>) => void; onRemove: () => void }) {
  const markup = item.priceMode === 'markup';
  const dMode = item.discountMode ?? 'geral';
  return (
    <Card className={clsx('p-4 sm:p-5', row?.level === 'negative' && 'border-bad', row?.level === 'low' && 'border-warn')}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0"><h3 className="truncate font-semibold">{item.name}</h3><p className="text-xs text-muted">{item.id.startsWith('m-') ? 'Item manual' : 'Do cadastro (alterações valem só aqui)'}</p></div>
        <div className="flex items-center gap-2">
          {row && <Badge tone={levelTone[row.level]}>{levelText(row.level, row.marginPct)}</Badge>}
          <button aria-label={`Remover ${item.name}`} onClick={onRemove} className="grid size-9 place-items-center rounded-lg text-muted hover:bg-bad/10 hover:text-bad"><Trash2 size={17} /></button>
        </div>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-3 md:grid-cols-4">
        <Num label="Quantidade" required value={item.qty} onChange={(v) => onChange({ qty: v ?? 0 })} />
        <Num label="Custo unit. (R$)" required value={item.cost} onChange={(v) => onChange({ cost: v ?? 0 })} />
        <Field label="Preço por">{(id) => <Select id={id} value={item.priceMode} onChange={(e) => onChange({ priceMode: e.target.value as 'price' | 'markup' })}><option value="price">Preço de venda</option><option value="markup">Markup %</option></Select>}</Field>
        {markup
          ? <Num label="Markup" placeholder="alvo geral" nullable value={item.markupPct} onChange={(v) => onChange({ markupPct: v })} suffix="%" />
          : <Num label="Preço de venda (R$)" required value={item.price} onChange={(v) => onChange({ price: v ?? 0 })} />}
        <Field label="Desconto">{(id) => (
          <Select id={id} value={dMode} onChange={(e) => onChange(e.target.value === 'geral' ? { discountMode: null, discount: 0 } : { discountMode: e.target.value as DiscountMode })}>
            <option value="geral">Usar geral</option><option value="pct">Em %</option><option value="brl">Em R$ (total)</option>
          </Select>)}</Field>
        {item.discountMode ? <Num label="Valor do desconto" value={item.discount} onChange={(v) => onChange({ discount: v ?? 0 })} suffix={item.discountMode === 'pct' ? '%' : 'R$'} /> : <div className="hidden md:block" />}
        <Num label="Imposto" placeholder="geral" nullable value={item.taxPct} onChange={(v) => onChange({ taxPct: v })} suffix="%" />
        <Num label="Custos extras (R$)" value={item.extra} onChange={(v) => onChange({ extra: v ?? 0 })} />
      </div>
      {hasTiers && <label className="mt-3 flex items-center gap-2 text-sm"><input type="checkbox" className="size-4 accent-[var(--pink)]" checked={item.useTiers} onChange={(e) => onChange({ useTiers: e.target.checked })} />Usar faixas por quantidade{row && item.useTiers && row.discountValue > 0 ? ` (desconto aplicado: ${percent2(row.discountPct)})` : ''}</label>}
      {row && (
        <>
          <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-2 border-t border-dashed border-line pt-3 text-sm sm:grid-cols-4 lg:grid-cols-8">
            <Mini k="Preço unit." v={money(row.unitPrice)} />
            <Mini k="Preço final unit." v={money(row.finalUnit)} strong />
            <Mini k="Total bruto" v={money(row.gross)} />
            <Mini k="Desconto" v={`${money(row.discountValue)}`} sub={percent2(row.discountPct)} />
            <Mini k="Total líquido" v={money(row.net)} strong />
            <Mini k="Lucro" v={money(row.profit)} strong tone={levelTone[row.level]} />
            <Mini k="Margem" v={percent2(row.marginPct)} tone={levelTone[row.level]} />
            <Mini k="Markup" v={percent2(row.markupPct)} />
          </dl>
          {row.issues.length > 0 && <ul className="mt-2 space-y-0.5 text-xs font-medium text-warn">{row.issues.map((s) => <li key={s} className="flex items-center gap-1.5"><TriangleAlert size={13} />{s}</li>)}</ul>}
        </>
      )}
    </Card>
  );
}

function Mini({ k, v, sub, strong, tone }: { k: string; v: string; sub?: string; strong?: boolean; tone?: 'ok' | 'warn' | 'bad' }) {
  return (
    <div>
      <dt className="text-xs text-muted">{k}</dt>
      <dd className={clsx('tnum', strong && 'font-semibold', tone === 'ok' && 'text-ok', tone === 'warn' && 'text-warn', tone === 'bad' && 'text-bad')}>{v}{sub && <span className="block text-xs font-normal text-muted">{sub}</span>}</dd>
    </div>
  );
}
