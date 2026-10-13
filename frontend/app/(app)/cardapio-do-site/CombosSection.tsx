'use client';
import { Minus, Pencil, Plus, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Button, Card, Field, Input, NumberInput, Switch } from '@/components/ui/form';
import { ConfirmDialog, Drawer } from '@/components/ui/Modal';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { fieldNum, money, parseNum, percent } from '@/lib/format';
import { comboCalc } from '@shared/combos';
import type { Combo, CombosPage } from '@shared/types';

type Draft = { id?: number; name: string; price: string; show: boolean; qty: Record<number, number> };

/** Seção Combos da página do Cardápio: lista, desconto sugerido e o editor. Toda rota de /api/combos devolve a página inteira, que substitui a atual. */
export function CombosSection({ initial }: { initial: CombosPage }) {
  const [page, setPage] = useState(initial);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [del, setDel] = useState<Combo | null>(null);
  const [discount, setDiscount] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // o servidor refez a página (ex.: produto ligado/desligado em cima): acompanha
  useEffect(() => setPage(initial), [initial]);

  const calc = draft ? comboCalc(
    Object.entries(draft.qty).flatMap(([id, qty]) => {
      const p = page.products.find((x) => x.id === Number(id));
      return p ? [{ qty, price: p.price ?? 0, unitCost: p.cost ?? 0 }] : [];
    }),
    { discountPct: page.discount_pct, minMarginPct: page.min_margin_pct, fixedPrice: parseNum(draft.price) > 0 ? parseNum(draft.price) : null },
  ) : null;
  const units = draft ? Object.values(draft.qty).reduce((a, b) => a + b, 0) : 0;

  function open(c?: Combo) {
    if (!c && !page.products.some((p) => p.on_menu)) return toast.warn('Coloque produtos no cardápio antes de montar um combo.');
    setDraft(c ? { id: c.id, name: c.name, price: c.price == null ? '' : fieldNum(c.price), show: c.show_on_menu, qty: Object.fromEntries(c.items.map((i) => [i.recipe_id, i.qty])) }
      : { name: '', price: '', show: true, qty: {} });
  }
  function step(id: number, delta: number) {
    if (!draft) return;
    const n = (draft.qty[id] ?? 0) + delta;
    const qty = { ...draft.qty };
    if (n <= 0) delete qty[id]; else qty[id] = Math.min(n, 99);
    setDraft({ ...draft, qty });
  }
  async function save() {
    if (!draft) return;
    if (!draft.name.trim()) return toast.error('Dê um nome para o combo.');
    if (units < 2) return toast.error('Escolha pelo menos 2 unidades de produtos.');
    const body = { name: draft.name.trim(), price: parseNum(draft.price) > 0 ? parseNum(draft.price) : null, show_on_menu: draft.show, items: Object.entries(draft.qty).map(([id, qty]) => ({ recipe_id: Number(id), qty })) };
    setBusy(true);
    try {
      setPage(draft.id ? await api<CombosPage>(`/api/combos/${draft.id}`, 'PUT', body) : await api<CombosPage>('/api/combos', 'POST', body));
      toast.ok(draft.id ? 'Combo salvo' : 'Combo criado');
      setDraft(null);
    } catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function remove() {
    if (!del) return;
    setBusy(true);
    try { setPage(await api<CombosPage>(`/api/combos/${del.id}`, 'DELETE')); toast.ok('Combo excluído'); setDel(null); setDraft(null); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function saveDiscount() {
    if (discount == null) return;
    setBusy(true);
    try { setPage(await api<CombosPage>('/api/combos/settings', 'PUT', { discount_pct: parseNum(discount) })); setDiscount(null); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }

  return (
    <section>
      <h2 className="mb-1 font-display text-2xl font-semibold">Combos</h2>
      <p className="mb-3 max-w-2xl text-muted">Junte produtos num combo. O preço é sugerido sozinho (soma dos produtos com o desconto abaixo, nunca abaixo do custo + a margem mínima), e você pode escolher outro.</p>
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <p className="font-semibold">Desconto sugerido: {percent(page.discount_pct)}</p>
        {discount == null
          ? <Button variant="ghost" size="sm" onClick={() => setDiscount(fieldNum(page.discount_pct))}>Alterar</Button>
          : (
            <form className="flex items-center gap-2" onSubmit={(e) => { e.preventDefault(); saveDiscount(); }}>
              <NumberInput aria-label="Desconto sugerido (%)" className="w-24" value={discount} onValueChange={setDiscount} autoFocus />
              <span className="text-muted">%</span>
              <Button type="submit" size="sm" busy={busy}>Salvar</Button>
              <Button type="button" variant="ghost" size="sm" onClick={() => setDiscount(null)}>Cancelar</Button>
            </form>
          )}
      </div>
      <div className="grid gap-3 lg:grid-cols-2">
        {page.combos.map((c) => (
          <Card key={c.id} className="flex flex-col gap-1 p-4">
            <div className="flex items-start justify-between gap-3">
              <p className="font-semibold">{c.name}</p>
              <p className="font-display text-xl font-semibold">{money(c.final_price)}</p>
            </div>
            <p className="text-muted">{c.items.map((i) => `${i.qty} ${i.name}`).join(' + ')}</p>
            <p className="text-[13px] text-muted">{c.price != null ? 'Preço escolhido' : 'Preço sugerido'} · avulso {money(c.full_price)} · lucro {money(c.profit)} ({percent(c.margin_pct)})</p>
            {c.below_cost && <p className="text-[13px] font-bold text-bad">O preço não cobre o custo dos produtos ({money(c.cost)}).</p>}
            {!c.available && <p className="text-[13px] text-warn">Fora do cardápio: {c.unavailable_reason ?? ''}</p>}
            <div className="mt-1 flex gap-1">
              <Button variant="ghost" size="sm" onClick={() => open(c)}><Pencil size={15} />Editar</Button>
              <Button variant="danger" size="sm" aria-label={`Excluir ${c.name}`} onClick={() => setDel(c)}><Trash2 size={15} /></Button>
            </div>
          </Card>
        ))}
      </div>
      <Button className="mt-4" onClick={() => open()}><Plus size={18} />Novo combo</Button>

      <Drawer open={!!draft} onClose={() => setDraft(null)} title={draft?.id ? 'Combo' : 'Novo combo'}
        footer={<><Button variant="ghost" onClick={() => setDraft(null)}>Cancelar</Button><Button type="submit" form="combo-form" busy={busy}>Salvar combo</Button></>}>
        {draft && calc && (
          <form id="combo-form" className="flex flex-col gap-5" onSubmit={(e) => { e.preventDefault(); save(); }}>
            <Field label="Nome do combo">{(id) => <Input id={id} maxLength={60} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="Ex.: Combo Festa" />}</Field>
            <fieldset>
              <legend className="mb-2 text-sm font-medium">Produtos</legend>
              <div className="flex flex-col gap-2">
                {page.products.map((p) => {
                  const n = draft.qty[p.id] ?? 0;
                  return (
                    <div key={p.id} className="flex items-center gap-3 rounded-2xl border border-line p-3">
                      <div className="min-w-0 flex-1">
                        <p className="truncate font-semibold">{p.name}</p>
                        <p className={p.on_menu ? 'text-[13px] text-muted' : 'text-[13px] text-warn'}>{p.on_menu ? `${money(p.price ?? 0)} cada · custo ${money(p.cost ?? 0)}` : 'Fora do cardápio'}</p>
                      </div>
                      {n > 0 && <>
                        <Button type="button" variant="ghost" size="sm" aria-label={`Menos ${p.name}`} onClick={() => step(p.id, -1)}><Minus size={16} /></Button>
                        <span className="w-6 text-center font-bold">{n}</span>
                      </>}
                      <Button type="button" variant="soft" size="sm" aria-label={`Mais ${p.name}`} onClick={() => step(p.id, 1)}><Plus size={16} /></Button>
                    </div>
                  );
                })}
              </div>
            </fieldset>
            <div className="rounded-2xl bg-surface-2 p-4">
              <p className="text-sm text-muted">Avulso: {money(calc.fullPrice)} · Custo: {money(calc.cost)}</p>
              <p className="font-semibold">Sugerido: {money(calc.suggested)} ({percent(page.discount_pct)} de desconto)</p>
              <div className="mt-3">
                <Field label="Preço do combo (R$, opcional)" hint={parseNum(draft.price) > 0 ? 'Escolhido por você.' : 'Em branco usa o sugerido (e acompanha os preços dos produtos).'}>
                  {(id) => <NumberInput id={id} value={draft.price} onValueChange={(v) => setDraft({ ...draft, price: v })} placeholder={calc.suggested > 0 ? fieldNum(calc.suggested) : ''} />}
                </Field>
              </div>
              <p className={calc.belowCost ? 'mt-3 font-bold text-bad' : 'mt-3 font-bold'}>Lucro: {money(calc.profit)} ({percent(calc.marginPct)} sobre o custo) · o cliente economiza {money(calc.savings)}</p>
              {calc.belowCost && <p className="text-[13px] text-bad">Esse preço não cobre o custo dos produtos.</p>}
            </div>
            <label className="flex items-center justify-between gap-3">
              <span>
                <span className="block font-semibold">Mostrar na landing page</span>
                <span className="block text-sm text-muted">Só aparece se todos os produtos estiverem no cardápio.</span>
              </span>
              <Switch checked={draft.show} label="Mostrar o combo na landing page" onChange={(v) => setDraft({ ...draft, show: v })} />
            </label>
            {draft.id != null && <Button type="button" variant="danger" onClick={() => setDel(page.combos.find((c) => c.id === draft.id) ?? null)}><Trash2 size={16} />Excluir combo</Button>}
          </form>
        )}
      </Drawer>

      <ConfirmDialog open={!!del} busy={busy} title={`Excluir “${del?.name}”?`} onClose={() => setDel(null)} onConfirm={remove}
        message="O combo sai do cardápio. Pedidos antigos continuam salvos." />
    </section>
  );
}
