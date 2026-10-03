'use client';
import { AnimatePresence, motion } from 'motion/react';
import { ArrowLeft, ImagePlus, Plus, Trash2, TriangleAlert } from 'lucide-react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useMemo, useRef, useState } from 'react';
import { Button, Card, Field, NumberInput, Select, Textarea } from '@/components/ui/form';
import { ConfirmDialog } from '@/components/ui/Modal';
import { Counter } from '@/components/ui/Money';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { toJpeg, uploadRecipePhoto } from '@/lib/image-client';
import { money, moneyPrecise, number, parseNum, fieldNum, percent } from '@/lib/format';
import { calcRecipe, unitCost, withSalePrice } from '@shared/pricing';
import type { Category, Ingredient, RecipeRow, Settings } from '@shared/types';

type Row = { key: number; ingredient_id: string; qty: string };
let keySeq = 0;

export function RecipeEditor({ recipe, ingredients, categories, settings, fixedTotal }: { recipe: RecipeRow | null; ingredients: Ingredient[]; categories: Category[]; settings: Settings; fixedTotal: number }) {
  const router = useRouter();
  const [name, setName] = useState(recipe?.name ?? '');
  const [categoryId, setCategoryId] = useState(recipe?.category_id ? String(recipe.category_id) : '');
  const [yieldUnits, setYield] = useState(fieldNum(recipe?.yield_units ?? ''));
  const [margin, setMargin] = useState(fieldNum(recipe?.margin_pct ?? 100));
  const [unitWeight, setUnitWeight] = useState(fieldNum(recipe?.unit_weight_g || ''));
  const [salePrice, setSalePrice] = useState(fieldNum(recipe?.sale_price || ''));
  const [hours, setHours] = useState(fieldNum(recipe?.prep_hours ?? ''));
  const [notes, setNotes] = useState(recipe?.notes ?? '');
  const [rows, setRows] = useState<Row[]>(recipe?.items.map((i) => ({ key: ++keySeq, ingredient_id: String(i.ingredient_id), qty: fieldNum(i.qty) })) ?? [{ key: ++keySeq, ingredient_id: '', qty: '' }]);
  const [busy, setBusy] = useState(false);
  const [confirmDel, setConfirmDel] = useState(false);
  // foto da Landing page: em receita nova fica guardada (`pending`) até salvar
  const [photoV, setPhotoV] = useState<string | null>(recipe?.photo_v ?? null);
  const [pending, setPending] = useState<Blob | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [photoBusy, setPhotoBusy] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const photoSrc = preview ?? (recipe && photoV ? `/api/fotos/${recipe.id}?v=${photoV}` : null);

  const byId = useMemo(() => new Map(ingredients.map((i) => [String(i.id), i])), [ingredients]);
  const lines = rows.map((r) => {
    const ing = byId.get(r.ingredient_id);
    const uc = ing ? unitCost(ing.package_cost, ing.package_size) : 0;
    return { ...r, ing, uc, used: parseNum(r.qty) * uc };
  });
  const calc = calcRecipe({
    items: lines.map((l) => ({ qty: parseNum(l.qty), unitCost: l.uc })), yieldUnits: parseNum(yieldUnits) || 1, marginPct: parseNum(margin),
    prepHours: parseNum(hours), hourlyRate: settings.hourly_rate, fixedMonthly: fixedTotal, monthlyUnits: settings.monthly_units,
  });
  const p = withSalePrice(calc, parseNum(salePrice)); // preço do cardápio fixado vale no lugar do sugerido
  const totalWeight = parseNum(unitWeight) * (parseNum(yieldUnits) || 1);
  const priciest = lines.length > 1 ? [...lines].sort((a, b) => b.used - a.used)[0] : null;
  const setRow = (key: number, patch: Partial<Row>) => setRows((rs) => rs.map((r) => (r.key === key ? { ...r, ...patch } : r)));

  async function save() {
    setBusy(true);
    const body = { name, category_id: categoryId, yield_units: parseNum(yieldUnits) || 1, margin_pct: parseNum(margin), prep_hours: parseNum(hours), unit_weight_g: parseNum(unitWeight), sale_price: parseNum(salePrice) || null, notes, items: rows.filter((r) => r.ingredient_id).map((r) => ({ ingredient_id: r.ingredient_id, qty: parseNum(r.qty) })) };
    try {
      if (recipe) { await api(`/api/recipes/${recipe.id}`, 'PUT', body); toast.ok('Receita salva'); router.refresh(); }
      else { const r = await api('/api/recipes', 'POST', body); if (pending) await uploadRecipePhoto(r.id, pending).catch((e) => toast.warn(`Receita criada, mas a foto não foi enviada: ${e.message}`)); toast.ok('Receita criada'); router.replace(`/receitas/${r.id}`); router.refresh(); }
    } catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function onPick(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    if (file.size > 15_000_000) { toast.warn('Essa foto é muito pesada. Escolha uma de até 15 MB.'); return; }
    setPhotoBusy(true);
    try {
      const blob = await toJpeg(file);
      if (recipe) { await uploadRecipePhoto(recipe.id, blob); setPhotoV(String(Date.now())); setPending(null); toast.ok('Foto salva'); router.refresh(); }
      else setPending(blob);
      setPreview(URL.createObjectURL(blob));
    } catch (err: any) { toast.error(err.message); } finally { setPhotoBusy(false); }
  }
  async function removePhoto() {
    setPhotoBusy(true);
    try {
      if (recipe && photoV) { await api(`/api/recipes/${recipe.id}/photo`, 'DELETE'); toast.ok('Foto removida'); router.refresh(); }
      setPending(null); setPreview(null); setPhotoV(null);
    } catch (err: any) { toast.error(err.message); } finally { setPhotoBusy(false); }
  }
  async function remove() {
    setBusy(true);
    try { await api(`/api/recipes/${recipe!.id}`, 'DELETE'); toast.ok('Receita excluída'); router.replace('/receitas'); router.refresh(); }
    catch (e: any) { toast.error(e.message); setBusy(false); }
  }

  const share = (v: number) => (p.rounded * (parseNum(yieldUnits) || 1) > 0 ? Math.max(0, (v / (p.rounded * (parseNum(yieldUnits) || 1))) * 100) : 0);
  const segs = [
    { label: 'Ingredientes', v: p.ingredients, color: 'var(--pink)' },
    { label: 'Mão de obra', v: p.labor, color: 'var(--ink)' },
    { label: 'Gastos rateados', v: p.overhead, color: 'var(--warn)' },
    { label: 'Lucro', v: Math.max(0, p.profitTotal), color: 'var(--ok)' },
  ];

  return (
    <>
      <Link href="/receitas" className="mb-5 inline-flex items-center gap-1.5 text-sm font-medium text-muted hover:text-ink"><ArrowLeft size={16} />Receitas</Link>
      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_380px]">
        <div className="min-w-0">
          <input maxLength={80} aria-label="Nome da receita" value={name} onChange={(e) => setName(e.target.value)} placeholder="Nome da receita"
            className="font-display w-full bg-transparent text-[38px] font-semibold leading-tight placeholder:text-muted/50 focus:outline-none" />
          <div className="mt-1 h-0.5 w-16 rounded bg-pink" />

          <Card className="mt-6 grid grid-cols-2 gap-4 p-5 md:grid-cols-4">
            <Field label="Categoria" className="col-span-2">{(id) => (
              <Select id={id} value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
                <option value="">Sem categoria</option>{categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </Select>)}</Field>
            <Field label="Rende (unidades)" className="col-span-1">{(id) => <NumberInput id={id} value={yieldUnits} onValueChange={(v) => setYield(v)} placeholder="Ex.: 20" />}</Field>
            <Field label="Preço do cardápio (R$, opcional)" className="col-span-2" hint={parseNum(salePrice) > 0 ? `Fixado por você. O cálculo sugere ${money(calc.rounded)}; a margem real com o preço fixado é ${percent(p.realMarginPct)}.` : `Deixe em branco para usar o preço calculado (${money(calc.rounded)}). Fixe um valor para ser avisada se a margem cair quando um ingrediente subir.`}>{(id) => <NumberInput id={id} value={salePrice} onValueChange={(v) => setSalePrice(v)} placeholder={calc.rounded > 0 ? fieldNum(calc.rounded) : ''} />}</Field>
            <Field label="Gramatura por unidade (g)" className="col-span-2" hint={totalWeight > 0 ? `Receita inteira: ${number(totalWeight)} g (${number(parseNum(yieldUnits) || 1)} un × ${number(parseNum(unitWeight))} g)` : 'Quanto pesa cada unidade pronta'}>{(id) => <NumberInput id={id} value={unitWeight} onValueChange={(v) => setUnitWeight(v)} placeholder="Ex.: 60" />}</Field>
            <Field label="Margem de lucro (%)" className="col-span-1">{(id) => <NumberInput id={id} value={margin} onValueChange={(v) => setMargin(v)} />}</Field>
            <Field label="Tempo de produção (horas)" className="col-span-2" hint={`Valor da hora: ${money(settings.hourly_rate)} (em Custos e ajustes)`}>{(id) => <NumberInput id={id} value={hours} onValueChange={(v) => setHours(v)} placeholder="Ex.: 1,5" />}</Field>
          </Card>

          <Card className="mt-4 p-5">
            <div className="flex gap-4">
              <div className="grid size-24 shrink-0 place-items-center overflow-hidden rounded-2xl bg-surface-2 text-muted">
                {photoSrc ? <img src={photoSrc} alt="Foto da receita" className="size-full object-cover" /> : <ImagePlus size={28} />}
              </div>
              <div className="min-w-0 flex-1">
                <h3 className="font-semibold">Foto para a Landing page <span className="font-normal text-muted">(opcional)</span></h3>
                <p className="mt-0.5 text-sm text-muted">Na Landing page aparecem apenas a foto, o nome e o preço por unidade. Ingredientes, custos e margem nunca são exibidos.</p>
                <p className="mt-1 text-sm font-medium">{p.rounded > 0 ? <>Preço exibido: <span className="tnum text-pink-strong">{money(p.rounded)}</span></> : <span className="text-warn">Sem ingredientes a receita fica sem preço e não aparece na Landing page.</span>}</p>
                <div className="mt-3 flex flex-wrap gap-2">
                  <Button variant="soft" size="sm" busy={photoBusy} onClick={() => fileRef.current?.click()}>{photoSrc ? 'Trocar foto' : 'Adicionar foto'}</Button>
                  {photoSrc && <Button variant="danger" size="sm" disabled={photoBusy} onClick={removePhoto}>Remover</Button>}
                </div>
                {!recipe && pending && <p className="mt-2 text-xs text-muted">A foto é enviada quando você salvar a receita.</p>}
                <input ref={fileRef} type="file" accept="image/jpeg,image/png,image/webp" hidden onChange={onPick} />
              </div>
            </div>
          </Card>

          <h2 className="font-display mb-3 mt-9 text-2xl font-semibold">Ingredientes</h2>
          {ingredients.length === 0 && (
            <p className="mb-3 flex items-center gap-2 rounded-2xl bg-warn/12 px-4 py-3 text-sm text-warn"><TriangleAlert size={17} />Seu estoque está vazio. <Link href="/estoque" className="font-semibold underline">Cadastre ingredientes</Link> para usá-los aqui.</p>
          )}
          <Card className="overflow-hidden">
            <div className="hidden grid-cols-[minmax(0,2fr)_1fr_1fr_1fr_40px] gap-3 border-b border-line bg-surface-2/60 px-4 py-2.5 text-[13px] font-semibold text-muted md:grid">
              <span>Ingrediente</span><span>Quantidade usada</span><span>Custo por unidade</span><span className="text-right">Custo usado</span><span />
            </div>
            <ul>
              <AnimatePresence initial={false}>
                {lines.map((l) => (
                  <motion.li key={l.key} layout initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }} transition={{ duration: 0.22 }} className="overflow-hidden border-b border-line last:border-0">
                    <div className="grid grid-cols-[1fr_auto] items-center gap-3 px-4 py-3 md:grid-cols-[minmax(0,2fr)_1fr_1fr_1fr_40px]">
                      <Select aria-label="Ingrediente" value={l.ingredient_id} onChange={(e) => setRow(l.key, { ingredient_id: e.target.value })} className="col-span-1">
                        <option value="">Escolha um ingrediente…</option>
                        {ingredients.filter((i) => String(i.id) === l.ingredient_id || !rows.some((r) => r.ingredient_id === String(i.id))).map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
                      </Select>
                      <button onClick={() => setRows((rs) => (rs.length > 1 ? rs.filter((r) => r.key !== l.key) : [{ ...rs[0], ingredient_id: '', qty: '' }]))} aria-label="Remover ingrediente" className="grid size-10 place-items-center rounded-lg text-muted hover:bg-bad/10 hover:text-bad md:order-last"><Trash2 size={17} /></button>
                      <div className="relative">
                        <NumberInput aria-label="Quantidade usada" value={l.qty} onValueChange={(v) => setRow(l.key, { qty: v })} placeholder="0" className="pr-10" />
                        <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted">{l.ing?.unit ?? ''}</span>
                      </div>
                      <p className="tnum text-sm text-muted">{l.ing ? `${moneyPrecise(l.uc)} /${l.ing.unit}` : '—'}</p>
                      <p className="tnum text-right font-semibold"><Counter value={l.used} kind="money" /></p>
                    </div>
                  </motion.li>
                ))}
              </AnimatePresence>
            </ul>
            <div className="p-3"><Button variant="soft" size="sm" onClick={() => setRows((rs) => [...rs, { key: ++keySeq, ingredient_id: '', qty: '' }])}><Plus size={16} />Adicionar ingrediente</Button></div>
          </Card>
          {priciest && priciest.used > 0 && <p className="mt-3 text-sm text-muted">O ingrediente mais caro desta receita é <strong className="text-ink">{priciest.ing?.name}</strong>, {number(Math.round((priciest.used / Math.max(p.ingredients, 0.0001)) * 100))}% do custo de ingredientes.</p>}

          <Field label="Modo de preparo e observações" className="mt-8">{(id) => <Textarea maxLength={500} id={id} value={notes} onChange={(e) => setNotes(e.target.value)} className="min-h-28" />}</Field>

          <div className="mt-6 flex flex-wrap items-center gap-3">
            <Button busy={busy} onClick={save} disabled={!name.trim()}>Salvar receita</Button>
            {recipe && <Button variant="danger" onClick={() => setConfirmDel(true)}><Trash2 size={17} />Excluir</Button>}
          </div>
        </div>

        {/* Etiqueta de preço */}
        <aside className="lg:sticky lg:top-24 lg:self-start" aria-label="Cálculo de preço">
          <div className="relative rounded-[28px] border border-line bg-surface shadow-card">
            <div className="absolute left-1/2 top-4 size-4 -translate-x-1/2 rounded-full border border-line bg-bg" aria-hidden />
            <div className="px-6 pb-2 pt-12">
              <p className="text-sm text-muted">Preço sugerido por unidade</p>
              <p className="font-display mt-1 text-[64px] font-semibold leading-none text-pink-strong"><Counter value={p.rounded} /></p>
              <p className="mt-2 text-sm text-muted">Calculado: <span className="tnum font-semibold text-ink">{money(p.suggested)}</span>, arredondado para cima em R$ 0,50.</p>
            </div>
            <div className="mx-6 my-4 h-px" style={{ background: 'repeating-linear-gradient(90deg, var(--line) 0 6px, transparent 6px 12px)' }} />
            <dl className="space-y-2.5 px-6 text-[15px]">
              <Line label="Ingredientes" v={p.ingredients} />
              <Line label={`Mão de obra (${number(parseNum(hours))} h)`} v={p.labor} />
              <Line label="Rateio de gastos" v={p.overhead} />
              <div className="flex items-baseline justify-between border-t border-line pt-2.5 font-semibold"><dt>Custo total da receita</dt><dd className="tnum"><Counter value={p.total} /></dd></div>
              <div className="flex items-baseline justify-between font-semibold"><dt>Custo por unidade</dt><dd className="tnum"><Counter value={p.perUnit} /></dd></div>
            </dl>
            <div className="mx-6 mt-5 rounded-2xl bg-ok/12 p-4 text-ok">
              <div className="flex items-baseline justify-between"><span className="text-sm font-medium">Lucro por unidade</span><span className="font-display text-2xl font-semibold"><Counter value={p.profitPerUnit} /></span></div>
              <div className="mt-1 flex items-baseline justify-between text-sm"><span>Lucro total da receita</span><span className="tnum font-semibold"><Counter value={p.profitTotal} /></span></div>
              <div className="mt-1 flex items-baseline justify-between text-sm"><span>Margem sobre o preço</span><span className="tnum font-semibold"><Counter value={p.realMarginPct} kind="percent" /></span></div>
            </div>
            <div className="px-6 pb-6 pt-5">
              <p className="mb-2 text-sm font-medium">Para onde vai cada R$ 1 do preço</p>
              <div className="flex h-3.5 overflow-hidden rounded-full bg-surface-2" role="img" aria-label="Divisão do preço entre ingredientes, mão de obra, gastos e lucro">
                {segs.map((s) => <motion.div key={s.label} animate={{ width: `${share(s.v)}%` }} transition={{ type: 'spring', stiffness: 160, damping: 26 }} style={{ background: s.color }} />)}
              </div>
              <ul className="mt-3 grid grid-cols-2 gap-x-3 gap-y-1.5 text-[13px]">
                {segs.map((s) => <li key={s.label} className="flex items-center gap-2 text-muted"><span className="size-2.5 rounded-full" style={{ background: s.color }} />{s.label} <span className="tnum ml-auto font-semibold text-ink">{Math.round(share(s.v))}%</span></li>)}
              </ul>
            </div>
          </div>
        </aside>
      </div>
      <ConfirmDialog open={confirmDel} busy={busy} title={`Excluir “${recipe?.name}”?`} message="A receita e a lista de ingredientes dela serão apagadas. Pedidos antigos continuam salvos." onClose={() => setConfirmDel(false)} onConfirm={remove} />
    </>
  );
}

function Line({ label, v }: { label: string; v: number }) {
  return <div className="flex items-baseline justify-between text-muted"><dt>{label}</dt><dd className="tnum text-ink"><Counter value={v} /></dd></div>;
}
