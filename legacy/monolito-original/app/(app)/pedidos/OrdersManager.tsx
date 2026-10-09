'use client';
import { AnimatePresence, motion } from 'motion/react';
import { ClipboardList, Pencil, Plus, Search, Trash2 } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import clsx from 'clsx';
import { EmptyState } from '@/components/PageHeader';
import { Badge } from '@/components/ui/Badge';
import { Button, Card, Field, Input, NumberInput, Select, Textarea } from '@/components/ui/form';
import { ConfirmDialog, Drawer } from '@/components/ui/Modal';
import { Counter } from '@/components/ui/Money';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { dateBR, money, number, parseNum } from '@/lib/format';
import { ORDER_PAYMENT, ORDER_STATUS } from '@/lib/order-labels';
import type { Order } from '@/lib/queries';

type Recipe = { id: number; name: string; price: number };
type Draft = { id?: number; order_date: string; customer: string; recipe_id: string; product: string; qty: string; unit_price: string; delivery_date: string; status: string; payment: string; notes: string };
const today = () => new Date().toLocaleDateString('sv-SE');
const blank = (): Draft => ({ order_date: today(), customer: '', recipe_id: '', product: '', qty: '1', unit_price: '', delivery_date: '', status: 'novo', payment: 'pendente', notes: '' });
const toneOf = (list: readonly { id: string; tone: any }[], id: string) => list.find((s) => s.id === id)?.tone ?? 'neutral';

export function OrdersManager({ orders, recipes }: { orders: Order[]; recipes: Recipe[] }) {
  const router = useRouter();
  const [filter, setFilter] = useState('all');
  const [search, setSearch] = useState('');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [del, setDel] = useState<Order | null>(null);
  const [busy, setBusy] = useState(false);

  const counts = Object.fromEntries(ORDER_STATUS.map((s) => [s.id, orders.filter((o) => o.status === s.id).length]));
  const rows = orders.filter((o) => (filter === 'all' || o.status === filter) && (!search || `${o.customer} ${o.product} ${o.number}`.toLowerCase().includes(search.toLowerCase())));
  const open = orders.filter((o) => !['entregue', 'cancelado'].includes(o.status));
  const receivable = orders.filter((o) => o.status !== 'cancelado' && o.payment !== 'pago').reduce((s, o) => s + o.qty * o.unit_price, 0);

  const edit = (o: Order) => setDraft({ id: o.id, order_date: o.order_date, customer: o.customer, recipe_id: o.recipe_id ? String(o.recipe_id) : '', product: o.product, qty: String(o.qty), unit_price: String(o.unit_price), delivery_date: o.delivery_date ?? '', status: o.status, payment: o.payment, notes: o.notes });

  async function save() {
    if (!draft) return;
    setBusy(true);
    try {
      if (draft.id) await api(`/api/orders/${draft.id}`, 'PATCH', draft); else await api('/api/orders', 'POST', draft);
      toast.ok(draft.id ? 'Pedido atualizado' : 'Pedido registrado');
      setDraft(null); router.refresh();
    } catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function quick(o: Order, patch: { status?: string; payment?: string }) {
    try { await api(`/api/orders/${o.id}`, 'PATCH', patch); router.refresh(); } catch (e: any) { toast.error(e.message); }
  }
  async function remove() {
    if (!del) return;
    setBusy(true);
    try { await api(`/api/orders/${del.id}`, 'DELETE'); toast.ok('Pedido excluído'); setDel(null); router.refresh(); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  function pickRecipe(id: string) {
    const r = recipes.find((x) => String(x.id) === id);
    setDraft((d) => d && ({ ...d, recipe_id: id, ...(r ? { product: r.name, unit_price: String(r.price) } : {}) }));
  }

  return (
    <>
      <div className="mb-6 grid grid-cols-2 gap-3 md:grid-cols-3">
        <Card className="p-4"><p className="text-sm text-muted">Em andamento</p><p className="font-display mt-1 text-3xl font-semibold"><Counter value={open.length} kind="int" /></p></Card>
        <Card className="p-4"><p className="text-sm text-muted">A receber</p><p className="font-display mt-1 text-3xl font-semibold"><Counter value={receivable} /></p></Card>
        <Card className="col-span-2 flex items-center justify-between p-4 md:col-span-1">
          <div><p className="text-sm text-muted">Total de pedidos</p><p className="font-display mt-1 text-3xl font-semibold"><Counter value={orders.length} kind="int" /></p></div>
          <Button onClick={() => setDraft(blank())}><Plus size={18} />Novo</Button>
        </Card>
      </div>

      <div className="mb-4 relative">
        <Search size={17} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" />
        <Input aria-label="Buscar pedido" placeholder="Buscar por cliente, produto ou número" value={search} onChange={(e) => setSearch(e.target.value)} className="pl-10" />
      </div>
      <div className="-mx-4 mb-5 flex gap-2 overflow-x-auto px-4 pb-1 md:mx-0 md:px-0">
        {[{ id: 'all', label: 'Todos', n: orders.length }, ...ORDER_STATUS.map((s) => ({ id: s.id, label: s.label, n: counts[s.id] }))].map((f) => (
          <button key={f.id} onClick={() => setFilter(f.id)} className={clsx('shrink-0 rounded-full border px-3.5 py-1.5 text-[13px] font-semibold transition-colors', filter === f.id ? 'border-pink bg-pink text-on-pink' : 'border-line bg-surface text-muted hover:text-ink')}>
            {f.label} <span className="tnum opacity-70">{f.n}</span>
          </button>
        ))}
      </div>

      {orders.length === 0 ? (
        <EmptyState icon={<ClipboardList size={26} />} title="Nenhum pedido registrado" text="Registre a primeira encomenda. Se escolher uma receita, o preço já vem preenchido." action={<Button onClick={() => setDraft(blank())}><Plus size={18} />Novo pedido</Button>} />
      ) : rows.length === 0 ? (
        <p className="rounded-3xl border border-dashed border-line px-6 py-12 text-center text-muted">Nenhum pedido encontrado.</p>
      ) : (
        <Card className="overflow-hidden">
          <div className="hidden grid-cols-[52px_minmax(0,1.6fr)_minmax(0,1.5fr)_1fr_1fr_1.1fr_1.1fr_76px] gap-3 border-b border-line bg-surface-2/60 px-5 py-3 text-[13px] font-semibold text-muted lg:grid">
            <span>Nº</span><span>Cliente</span><span>Produto</span><span>Entrega</span><span>Total</span><span>Status</span><span>Pagamento</span><span />
          </div>
          <ul>
            <AnimatePresence initial={false}>
              {rows.map((o) => (
                <motion.li key={o.id} layout initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0, height: 0 }}
                  className="grid grid-cols-2 items-center gap-x-3 gap-y-2.5 border-b border-line px-5 py-4 last:border-0 lg:grid-cols-[52px_minmax(0,1.6fr)_minmax(0,1.5fr)_1fr_1fr_1.1fr_1.1fr_76px] lg:py-3">
                  <span className="tnum font-display text-lg font-semibold text-pink-strong">{String(o.number).padStart(2, '0')}</span>
                  <p className="truncate text-right font-semibold lg:text-left">{o.customer}</p>
                  <p className="col-span-2 truncate text-sm lg:col-span-1"><span className="tnum text-muted">{number(o.qty)}× </span>{o.product}</p>
                  <p className="text-sm text-muted"><span className="lg:hidden">Entrega: </span>{dateBR(o.delivery_date)}</p>
                  <p className="tnum text-right font-semibold lg:text-left">{money(o.qty * o.unit_price)}</p>
                  <StateSelect value={o.status} options={ORDER_STATUS} label={`Status do pedido ${o.number}`} onChange={(v) => quick(o, { status: v })} />
                  <StateSelect value={o.payment} options={ORDER_PAYMENT} label={`Pagamento do pedido ${o.number}`} onChange={(v) => quick(o, { payment: v })} />
                  <div className="col-span-2 flex justify-end gap-0.5 lg:col-span-1">
                    <Button variant="ghost" size="sm" aria-label={`Editar pedido ${o.number}`} onClick={() => edit(o)}><Pencil size={16} /></Button>
                    <Button variant="danger" size="sm" aria-label={`Excluir pedido ${o.number}`} onClick={() => setDel(o)}><Trash2 size={16} /></Button>
                  </div>
                </motion.li>
              ))}
            </AnimatePresence>
          </ul>
        </Card>
      )}

      <Drawer open={!!draft} onClose={() => setDraft(null)} title={draft?.id ? 'Editar pedido' : 'Novo pedido'}
        footer={<><Button variant="ghost" onClick={() => setDraft(null)}>Cancelar</Button><Button type="submit" form="order-form" busy={busy}>Salvar pedido</Button></>}>
        {draft && (
          <form id="order-form" className="grid grid-cols-2 gap-4" onSubmit={(e) => { e.preventDefault(); save(); }}>
            <Field label="Cliente" className="col-span-2">{(id) => <Input maxLength={80} id={id} value={draft.customer} onChange={(e) => setDraft({ ...draft, customer: e.target.value })} />}</Field>
            <Field label="Receita (opcional)" className="col-span-2" hint="Preenche o produto e o preço sugerido.">{(id) => (
              <Select id={id} value={draft.recipe_id} onChange={(e) => pickRecipe(e.target.value)}>
                <option value="">Produto avulso</option>{recipes.map((r) => <option key={r.id} value={r.id}>{r.name} · {money(r.price)}</option>)}
              </Select>)}</Field>
            <Field label="Produto" className="col-span-2">{(id) => <Input maxLength={100} id={id} value={draft.product} onChange={(e) => setDraft({ ...draft, product: e.target.value })} />}</Field>
            <Field label="Quantidade">{(id) => <NumberInput id={id} value={draft.qty} onValueChange={(v) => setDraft({ ...draft, qty: v })} />}</Field>
            <Field label="Preço unitário (R$)">{(id) => <NumberInput id={id} value={draft.unit_price} onValueChange={(v) => setDraft({ ...draft, unit_price: v })} />}</Field>
            <div className="col-span-2 flex items-center justify-between rounded-2xl bg-pink-tint px-4 py-3 text-pink-strong">
              <span className="text-sm font-medium">Total do pedido</span><Counter value={parseNum(draft.qty) * parseNum(draft.unit_price)} className="font-display text-xl font-semibold" />
            </div>
            <Field label="Data do pedido">{(id) => <Input id={id} type="date" value={draft.order_date} onChange={(e) => setDraft({ ...draft, order_date: e.target.value })} />}</Field>
            <Field label="Entrega">{(id) => <Input id={id} type="date" value={draft.delivery_date} onChange={(e) => setDraft({ ...draft, delivery_date: e.target.value })} />}</Field>
            <Field label="Status">{(id) => <Select id={id} value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value })}>{ORDER_STATUS.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}</Select>}</Field>
            <Field label="Pagamento">{(id) => <Select id={id} value={draft.payment} onChange={(e) => setDraft({ ...draft, payment: e.target.value })}>{ORDER_PAYMENT.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}</Select>}</Field>
            <Field label="Observações" className="col-span-2">{(id) => <Textarea maxLength={300} id={id} value={draft.notes} onChange={(e) => setDraft({ ...draft, notes: e.target.value })} />}</Field>
          </form>
        )}
      </Drawer>
      <ConfirmDialog open={!!del} busy={busy} title={`Excluir o pedido ${del?.number}?`} message="Ele deixa de contar nas metas do mês. Para manter o histórico, prefira marcar como Cancelado." onClose={() => setDel(null)} onConfirm={remove} />
    </>
  );
}

function StateSelect({ value, options, label, onChange }: { value: string; options: readonly { id: string; label: string; tone: any }[]; label: string; onChange: (v: string) => void }) {
  return (
    <div className="relative inline-flex">
      <Badge tone={toneOf(options, value)}>{options.find((o) => o.id === value)?.label}</Badge>
      <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)} className="absolute inset-0 cursor-pointer opacity-0">
        {options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
      </select>
    </div>
  );
}
