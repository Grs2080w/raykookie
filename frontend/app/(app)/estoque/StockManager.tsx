'use client';
import { AnimatePresence, motion } from 'motion/react';
import { Minus, PackageSearch, Pencil, Plus, Search, Tags, Trash2 } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useMemo, useState } from 'react';
import clsx from 'clsx';
import { CategoryChip } from '@/components/CategoryIcon';
import { EmptyState } from '@/components/PageHeader';
import { Badge } from '@/components/ui/Badge';
import { Button, Card, Field, Input, NumberInput, Select, Textarea } from '@/components/ui/form';
import { ConfirmDialog, Drawer } from '@/components/ui/Modal';
import { Counter } from '@/components/ui/Money';
import { PricesDrawer } from '@/components/PricesDrawer';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { money, moneyPrecise, number, parseNum, fieldNum } from '@/lib/format';
import { stockStatus, unitCost } from '@shared/pricing';
import type { Category, Ingredient, ProductionRow } from '@shared/types';

type Draft = { id?: number; name: string; category_id: string; unit: string; stock_qty: string; package_size: string; package_cost: string; min_stock: string; supplier: string; notes: string };
const blank: Draft = { name: '', category_id: '', unit: 'g', stock_qty: '1', package_size: '', package_cost: '', min_stock: '', supplier: '', notes: '' };
const STATUS = { ok: { tone: 'ok', label: 'Estoque ok' }, baixo: { tone: 'warn', label: 'Estoque baixo' }, zerado: { tone: 'bad', label: 'Acabou' } } as const;

export function StockManager({ ingredients, categories, producible }: { ingredients: Ingredient[]; categories: Category[]; producible: ProductionRow[] }) {
  const router = useRouter();
  const [search, setSearch] = useState('');
  const [cat, setCat] = useState<number | 'low' | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [del, setDel] = useState<Ingredient | null>(null);
  const [busy, setBusy] = useState(false);
  const [prices, setPrices] = useState<Ingredient | null>(null);
  const catMap = useMemo(() => new Map(categories.map((c) => [c.id, c])), [categories]);

  const rows = ingredients.filter((i) => {
    if (search && !`${i.name} ${i.supplier}`.toLowerCase().includes(search.toLowerCase())) return false;
    if (cat === 'low') return stockStatus(i.stock_qty, i.min_stock) !== 'ok';
    if (cat !== null) return i.category_id === cat;
    return true;
  });
  const low = ingredients.filter((i) => stockStatus(i.stock_qty, i.min_stock) !== 'ok').length;
  const stockValue = ingredients.reduce((s, i) => s + i.stock_qty * unitCost(i.package_cost, i.package_size), 0);

  const edit = (i: Ingredient) => setDraft({ id: i.id, name: i.name, category_id: i.category_id ? String(i.category_id) : '', unit: i.unit, stock_qty: fieldNum(i.stock_qty), package_size: fieldNum(i.package_size), package_cost: fieldNum(i.package_cost), min_stock: fieldNum(i.min_stock), supplier: i.supplier, notes: i.notes });

  async function save() {
    if (!draft) return;
    setBusy(true);
    try {
      if (draft.id) await api(`/api/ingredients/${draft.id}`, 'PATCH', draft); else await api('/api/ingredients', 'POST', draft);
      toast.ok(draft.id ? 'Ingrediente atualizado' : 'Ingrediente adicionado');
      setDraft(null); router.refresh();
    } catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function remove() {
    if (!del) return;
    setBusy(true);
    try { await api(`/api/ingredients/${del.id}`, 'DELETE'); toast.ok('Ingrediente excluído'); setDel(null); router.refresh(); }
    catch (e: any) { toast.error(e.message); setDel(null); } finally { setBusy(false); }
  }
  async function step(i: Ingredient, d: number) {
    const next = Math.max(0, Math.round((i.stock_qty + d) * 1000) / 1000);
    try { await api(`/api/ingredients/${i.id}`, 'PATCH', { stock_qty: next }); router.refresh(); } catch (e: any) { toast.error(e.message); }
  }

  // receitas com ingredientes: quantas unidades dá para produzir agora e quais ficam inviáveis por falta de ingrediente
  const makeable = producible.filter((r) => r.can_make !== null).sort((a, b) => (a.can_make ?? 0) - (b.can_make ?? 0) || a.name.localeCompare(b.name, 'pt-BR'));
  const blocked = makeable.filter((r) => r.can_make === 0);

  const perUnit = draft ? unitCost(parseNum(draft.package_cost), parseNum(draft.package_size)) : 0;

  return (
    <>
      <div className="mb-6 grid grid-cols-2 gap-3 md:grid-cols-3">
        <Card className="p-4"><p className="text-sm text-muted">Ingredientes</p><p className="font-display mt-1 text-3xl font-semibold"><Counter value={ingredients.length} kind="int" /></p></Card>
        <Card className="p-4"><p className="text-sm text-muted">Valor em estoque</p><p className="font-display mt-1 text-3xl font-semibold"><Counter value={stockValue} /></p></Card>
        <Card className={clsx('col-span-2 p-4 md:col-span-1', low > 0 && 'border-warn/50')}>
          <p className="text-sm text-muted">Pedem reposição</p>
          <p className={clsx('font-display mt-1 text-3xl font-semibold', low > 0 && 'text-warn')}><Counter value={low} kind="int" /></p>
        </Card>
      </div>

      {makeable.length > 0 && (
        <Card className="mb-6 p-5">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h2 className="font-display text-lg font-semibold">Dá para produzir</h2>
            {blocked.length > 0 ? <Badge tone="bad">{blocked.length} {blocked.length === 1 ? 'receita inviável' : 'receitas inviáveis'}</Badge> : <Badge tone="ok">Tudo viável</Badge>}
          </div>
          <ul className="grid gap-2 md:grid-cols-2">
            {makeable.map((r) => (
              <li key={r.recipe_id} className={clsx('flex items-center justify-between gap-3 rounded-2xl px-4 py-2.5 text-sm', r.can_make === 0 ? 'bg-bad/10' : 'bg-surface-2')}>
                <span className="min-w-0"><span className="block truncate font-semibold">{r.name}</span>
                  {r.can_make === 0 && r.blocking.length > 0 && <span className="block truncate text-xs text-bad">Falta: {r.blocking.join(', ')}</span>}</span>
                <span className={clsx('tnum shrink-0 font-semibold', r.can_make === 0 && 'text-bad')}>{r.can_make === 0 ? 'Inviável' : `${number(r.can_make ?? 0)} un`}</span>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="relative min-w-56 flex-1">
          <Search size={17} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" />
          <Input aria-label="Buscar ingrediente" placeholder="Buscar ingrediente ou fornecedor" value={search} onChange={(e) => setSearch(e.target.value)} className="pl-10" />
        </div>
        <Button onClick={() => setDraft({ ...blank })}><Plus size={18} />Novo ingrediente</Button>
      </div>

      <div className="-mx-4 mb-5 flex gap-2 overflow-x-auto px-4 pb-1 md:mx-0 md:flex-wrap md:px-0">
        {[{ id: null, label: 'Todos' }, { id: 'low' as const, label: `Repor${low ? ` (${low})` : ''}` }].map((f) => (
          <button key={String(f.id)} onClick={() => setCat(f.id)} className={clsx('shrink-0 rounded-full border px-3.5 py-1.5 text-[13px] font-semibold transition-colors', cat === f.id ? 'border-pink bg-pink text-on-pink' : 'border-line bg-surface text-muted hover:text-ink')}>{f.label}</button>
        ))}
        {categories.filter((c) => ingredients.some((i) => i.category_id === c.id)).map((c) => (
          <button key={c.id} onClick={() => setCat(cat === c.id ? null : c.id)} className={clsx('shrink-0 rounded-full border px-3.5 py-1.5 text-[13px] font-semibold transition-colors', cat === c.id ? 'border-pink bg-pink-tint text-pink-strong' : 'border-line bg-surface text-muted hover:text-ink')}>{c.name}</button>
        ))}
      </div>

      {ingredients.length === 0 ? (
        <EmptyState icon={<PackageSearch size={26} />} title="Seu estoque está vazio" text="Cadastre os ingredientes com o preço da embalagem. Eles alimentam o cálculo das receitas." action={<Button onClick={() => setDraft({ ...blank })}><Plus size={18} />Adicionar o primeiro</Button>} />
      ) : rows.length === 0 ? (
        <p className="rounded-3xl border border-dashed border-line px-6 py-12 text-center text-muted">Nenhum ingrediente com esse filtro.</p>
      ) : (
        <Card className="overflow-hidden">
          <div className="hidden grid-cols-[minmax(0,2.2fr)_1.1fr_1fr_1fr_1.2fr_auto] gap-4 border-b border-line bg-surface-2/60 px-5 py-3 text-[13px] font-semibold text-muted md:grid">
            <span>Ingrediente</span><span>Em estoque</span><span>Embalagem</span><span>Custo por unidade</span><span>Situação</span><span className="w-20" />
          </div>
          <ul>
            <AnimatePresence initial={false}>
              {rows.map((i) => {
                const st = STATUS[stockStatus(i.stock_qty, i.min_stock)];
                const c = i.category_id ? catMap.get(i.category_id) : null;
                return (
                  <motion.li key={i.id} layout initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0, height: 0 }}
                    className="grid grid-cols-2 items-center gap-x-4 gap-y-3 border-b border-line px-5 py-4 last:border-0 md:grid-cols-[minmax(0,2.2fr)_1.1fr_1fr_1fr_1.2fr_auto] md:py-3">
                    <div className="col-span-2 min-w-0 md:col-span-1">
                      <p className="truncate font-semibold">{i.name}</p>
                      <div className="mt-1 flex flex-wrap items-center gap-2">
                        {c && <CategoryChip name={c.name} icon={c.icon} color={c.color} size="sm" />}
                        {i.supplier && <span className="text-xs text-muted">{i.supplier}</span>}
                      </div>
                    </div>
                    <div className="flex items-center gap-1.5" aria-label={`Estoque de ${i.name}`}>
                      <button onClick={() => step(i, -1)} aria-label="Diminuir 1" className="grid size-8 place-items-center rounded-lg border border-line text-muted hover:border-pink hover:text-pink"><Minus size={14} /></button>
                      <span className="tnum min-w-10 text-center font-semibold">{number(i.stock_qty)}</span>
                      <button onClick={() => step(i, 1)} aria-label="Aumentar 1" className="grid size-8 place-items-center rounded-lg border border-line text-muted hover:border-pink hover:text-pink"><Plus size={14} /></button>
                    </div>
                    <div className="tnum text-sm"><span className="text-muted md:hidden">Embalagem: </span>{number(i.package_size)} {i.unit} · {money(i.package_cost)}</div>
                    <div className="tnum text-sm font-semibold"><span className="font-normal text-muted md:hidden">Custo: </span>{moneyPrecise(unitCost(i.package_cost, i.package_size))}<span className="font-normal text-muted"> /{i.unit}</span></div>
                    <div><Badge tone={st.tone}>{st.label}</Badge></div>
                    <div className="col-span-2 flex justify-end gap-0.5 md:col-span-1 md:w-20">
                      <Button variant="ghost" size="sm" aria-label={`Preços de ${i.name}`} onClick={() => setPrices(i)}><Tags size={16} /></Button>
                      <Button variant="ghost" size="sm" aria-label={`Editar ${i.name}`} onClick={() => edit(i)}><Pencil size={16} /></Button>
                      <Button variant="danger" size="sm" aria-label={`Excluir ${i.name}`} onClick={() => setDel(i)}><Trash2 size={16} /></Button>
                    </div>
                  </motion.li>
                );
              })}
            </AnimatePresence>
          </ul>
        </Card>
      )}

      <PricesDrawer ingredientId={prices?.id ?? null} name={prices?.name ?? ''} onClose={() => setPrices(null)} />

      <Drawer open={!!draft} onClose={() => setDraft(null)} title={draft?.id ? 'Editar ingrediente' : 'Novo ingrediente'}
        footer={<><Button variant="ghost" onClick={() => setDraft(null)}>Cancelar</Button><Button type="submit" form="stock-form" busy={busy}>Salvar</Button></>}>
        {draft && (
          <form id="stock-form" className="grid grid-cols-2 gap-4" onSubmit={(e) => { e.preventDefault(); save(); }}>
            <Field label="Nome" className="col-span-2">{(id) => <Input maxLength={80} id={id} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="Ex.: Leite condensado" />}</Field>
            <Field label="Categoria">{(id) => (
              <Select id={id} value={draft.category_id} onChange={(e) => setDraft({ ...draft, category_id: e.target.value })}>
                <option value="">Sem categoria</option>{categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </Select>)}</Field>
            <Field label="Unidade de medida">{(id) => (
              <Select id={id} value={draft.unit} onChange={(e) => setDraft({ ...draft, unit: e.target.value })}>
                <option value="g">Gramas (g)</option><option value="ml">Mililitros (ml)</option><option value="un">Unidades (un)</option>
              </Select>)}</Field>
            <Field label={`Tamanho da embalagem (${draft.unit})`}>{(id) => <NumberInput id={id} value={draft.package_size} onValueChange={(v) => setDraft({ ...draft, package_size: v })} placeholder="Ex.: 395" />}</Field>
            <Field label="Preço da embalagem (R$)">{(id) => <NumberInput id={id} value={draft.package_cost} onValueChange={(v) => setDraft({ ...draft, package_cost: v })} placeholder="Ex.: 7,50" />}</Field>
            <div className="col-span-2 flex items-center justify-between rounded-2xl bg-pink-tint px-4 py-3 text-pink-strong">
              <span className="text-sm font-medium">Custo por {draft.unit}</span>
              <Counter value={perUnit} kind="precise" className="font-display text-xl font-semibold" />
            </div>
            <Field label="Embalagens em estoque" hint="Quantas embalagens fechadas você tem.">{(id) => <NumberInput id={id} value={draft.stock_qty} onValueChange={(v) => setDraft({ ...draft, stock_qty: v })} />}</Field>
            <Field label="Estoque mínimo" hint="Avisa quando chegar nesse número.">{(id) => <NumberInput id={id} value={draft.min_stock} onValueChange={(v) => setDraft({ ...draft, min_stock: v })} />}</Field>
            <Field label="Fornecedor" className="col-span-2">{(id) => <Input maxLength={80} id={id} value={draft.supplier} onChange={(e) => setDraft({ ...draft, supplier: e.target.value })} />}</Field>
            <Field label="Observações" className="col-span-2">{(id) => <Textarea maxLength={300} id={id} value={draft.notes} onChange={(e) => setDraft({ ...draft, notes: e.target.value })} />}</Field>
          </form>
        )}
      </Drawer>
      <ConfirmDialog open={!!del} busy={busy} title={`Excluir “${del?.name}”?`} message="O ingrediente sai do estoque. Se ele estiver em alguma receita, o sistema avisa e não exclui." onClose={() => setDel(null)} onConfirm={remove} />
    </>
  );
}
