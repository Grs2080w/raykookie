'use client';
import { AnimatePresence, motion } from 'motion/react';
import { ClipboardList, MapPin, Pencil, Plus, Search, Trash2, X } from 'lucide-react';
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
import { dateBR, money, number, parseNum, fieldNum } from '@/lib/format';
import { ShopOrderActions, type ShopAction } from '@/components/ShopOrderActions';
import { ORDER_PAYMENT, ORDER_STATUS } from '@shared/order-labels';
import { FULL_REFUND_CODE, anyReasonLabel } from '@shared/refund';
import { paymentLabel } from '@shared/shop';
import Link from 'next/link';
import { groupOrders, orderTotal } from '@shared/order-groups';
import type { Order } from '@shared/types';

type Recipe = { id: number; name: string; price: number };
/** Um item do pedido em edição; `id` é a linha que ele já tem (ausente = item novo). */
type ItemDraft = { id?: number; recipe_id: string; product: string; qty: string; unit_price: string };
type Draft = { id?: number; shop?: boolean; order_date: string; customer: string; items: ItemDraft[]; delivery_date: string; status: string; payment: string; notes: string };
const today = () => new Date().toLocaleDateString('sv-SE');
const blankItem = (): ItemDraft => ({ recipe_id: '', product: '', qty: '1', unit_price: '' });
const blank = (): Draft => ({ order_date: today(), customer: '', items: [blankItem()], delivery_date: '', status: 'novo', payment: 'pendente', notes: '' });
const toneOf = (list: readonly { id: string; tone: any }[], id: string) => list.find((s) => s.id === id)?.tone ?? 'neutral';

export function OrdersManager({ orders, recipes }: { orders: Order[]; recipes: Recipe[] }) {
  const router = useRouter();
  const [filter, setFilter] = useState('all');
  const [search, setSearch] = useState('');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [del, setDel] = useState<Order[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [shopAct, setShopAct] = useState<ShopAction>(null);

  // pedido com vários itens: as linhas do mesmo pedido viram uma linha só (situação e pagamento valem para todas)
  const entries = groupOrders(orders);
  const counts = Object.fromEntries(ORDER_STATUS.map((s) => [s.id, entries.filter((g) => g[0].status === s.id).length]));
  const rows = entries.filter((g) => (filter === 'all' || g[0].status === filter) && (!search || g.some((o) => `${o.customer} ${o.product} ${o.number}`.toLowerCase().includes(search.toLowerCase()))));
  const open = entries.filter((g) => !['entregue', 'cancelado'].includes(g[0].status));
  const receivable = orders.filter((o) => o.status !== 'cancelado' && o.payment !== 'pago').reduce((s, o) => s + o.qty * o.unit_price, 0);

  const edit = (lines: Order[]) => {
    const o = lines[0];
    setDraft({ id: o.id, shop: !!o.shop_order_id, order_date: o.order_date, customer: o.customer, delivery_date: o.delivery_date ?? '', status: o.status, payment: o.payment, notes: o.notes,
      items: lines.map((l) => ({ id: l.id, recipe_id: l.recipe_id ? String(l.recipe_id) : '', product: l.product, qty: fieldNum(l.qty), unit_price: fieldNum(l.unit_price) })) });
  };

  async function save() {
    if (!draft) return;
    for (const [i, it] of draft.items.entries()) {
      const where = draft.items.length > 1 ? ` do item ${i + 1}` : '';
      if (!it.product.trim()) return toast.error(`Informe o produto${where}.`);
      if (parseNum(it.qty) <= 0) return toast.error(`A quantidade${where} precisa ser maior que zero.`);
    }
    setBusy(true);
    try {
      const { items, shop, ...header } = draft;
      // pedido do app não muda item nem valor: segue pelo formato de uma linha só (o servidor confere que não mudou)
      const body = shop ? { ...header, ...items[0] } : { ...header, items };
      if (draft.id) await api(`/api/orders/${draft.id}`, 'PATCH', body); else await api('/api/orders', 'POST', body);
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
    try { await api(`/api/orders/${del[0].id}`, 'DELETE'); toast.ok('Pedido excluído'); setDel(null); router.refresh(); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  function setItem(index: number, patch: Partial<ItemDraft>) {
    setDraft((d) => d && ({ ...d, items: d.items.map((it, i) => (i === index ? { ...it, ...patch } : it)) }));
  }
  function pickRecipe(index: number, id: string) {
    const r = recipes.find((x) => String(x.id) === id);
    setItem(index, { recipe_id: id, ...(r ? { product: r.name, unit_price: fieldNum(r.price) } : {}) });
  }

  return (
    <>
      <div className="mb-6 grid grid-cols-2 gap-3 md:grid-cols-3">
        <Card className="p-4"><p className="text-sm text-muted">Em andamento</p><p className="font-display mt-1 text-3xl font-semibold"><Counter value={open.length} kind="int" /></p></Card>
        <Card className="p-4"><p className="text-sm text-muted">A receber</p><p className="font-display mt-1 text-3xl font-semibold"><Counter value={receivable} /></p></Card>
        <Card className="col-span-2 flex items-center justify-between p-4 md:col-span-1">
          <div><p className="text-sm text-muted">Total de pedidos</p><p className="font-display mt-1 text-3xl font-semibold"><Counter value={entries.length} kind="int" /></p></div>
          <Button onClick={() => setDraft(blank())}><Plus size={18} />Novo</Button>
        </Card>
      </div>

      <div className="mb-4 relative">
        <Search size={17} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" />
        <Input aria-label="Buscar pedido" placeholder="Buscar por cliente, produto ou número" value={search} onChange={(e) => setSearch(e.target.value)} className="pl-10" />
      </div>
      <div className="-mx-4 mb-5 flex gap-2 overflow-x-auto px-4 pb-1 md:mx-0 md:px-0">
        {[{ id: 'all', label: 'Todos', n: entries.length }, ...ORDER_STATUS.map((s) => ({ id: s.id, label: s.label, n: counts[s.id] }))].map((f) => (
          <button key={f.id} onClick={() => setFilter(f.id)} className={clsx('shrink-0 rounded-full border px-3.5 py-1.5 text-[13px] font-semibold transition-colors', filter === f.id ? 'border-pink bg-pink text-on-pink' : 'border-line bg-surface text-muted hover:text-ink')}>
            {f.label} <span className="tnum opacity-70">{f.n}</span>
          </button>
        ))}
      </div>

      {entries.length === 0 ? (
        <EmptyState icon={<ClipboardList size={26} />} title="Nenhum pedido registrado" text="Registre a primeira encomenda. Adicione um item por produto; se escolher uma receita, o preço já vem preenchido." action={<Button onClick={() => setDraft(blank())}><Plus size={18} />Novo pedido</Button>} />
      ) : rows.length === 0 ? (
        <p className="rounded-3xl border border-dashed border-line px-6 py-12 text-center text-muted">Nenhum pedido encontrado.</p>
      ) : (
        <Card className="overflow-hidden">
          <div className="hidden grid-cols-[52px_minmax(0,1.6fr)_minmax(0,1.5fr)_1fr_1fr_1.1fr_1.1fr_76px] gap-3 border-b border-line bg-surface-2/60 px-5 py-3 text-[13px] font-semibold text-muted lg:grid">
            <span>Nº</span><span>Cliente</span><span>Produto</span><span>Entrega</span><span>Total</span><span>Status</span><span>Pagamento</span><span />
          </div>
          <ul>
            <AnimatePresence initial={false}>
              {rows.map((g) => { const o = g[0]; return (
                <motion.li key={o.id} layout initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0, height: 0 }}
                  className="grid grid-cols-2 items-center gap-x-3 gap-y-2.5 border-b border-line px-5 py-4 last:border-0 lg:grid-cols-[52px_minmax(0,1.6fr)_minmax(0,1.5fr)_1fr_1fr_1.1fr_1.1fr_76px] lg:py-3">
                  <span className="tnum font-display text-lg font-semibold text-pink-strong">{String(o.number).padStart(2, '0')}</span>
                  <p className="truncate text-right font-semibold lg:text-left">{o.customer}</p>
                  <div className="col-span-2 text-sm lg:col-span-1">
                    {g.map((l) => <p key={l.id} className="truncate"><span className="tnum text-muted">{number(l.qty)}× </span>{l.product}{g.length > 1 && <span className="tnum text-muted"> · {money(l.qty * l.unit_price)}</span>}</p>)}
                    {o.shop_order_id && o.shop_payment_method && <span className="text-muted"> · {paymentLabel(o.shop_payment_method)}{o.shop_payment_method === 'cartao' && (o.shop_installments ?? 1) > 1 ? ` ${o.shop_installments}x` : ''}</span>}
                  </div>
                  {o.address && <p className="col-span-full flex items-start gap-1.5 text-sm"><MapPin size={15} className="mt-0.5 shrink-0 text-pink-strong" /><span><span className="font-semibold">Endereço: </span>{o.address}</span></p>}
                  <p className="text-sm text-muted"><span className="lg:hidden">Entrega: </span>{dateBR(o.delivery_date)}</p>
                  <p className="tnum text-right font-semibold lg:text-left">{money(orderTotal(g))}</p>
                  <StateSelect value={o.status} options={ORDER_STATUS} label={`Status do pedido ${o.number}`} disabled={!!o.shop_cancel} onChange={(v) => (v === 'cancelado' && o.shop_order_id ? setShopAct({ order: o, mode: 'cancelar' }) : quick(o, { status: v }))} />
                  <StateSelect value={o.payment} options={ORDER_PAYMENT} label={`Pagamento do pedido ${o.number}`} disabled={!!o.shop_cancel} onChange={(v) => quick(o, { payment: v })} />
                  {o.shop_order_id && <ShopBands o={o} onAct={(mode) => setShopAct({ order: o, mode })} />}
                  <div className="col-span-2 flex justify-end gap-0.5 lg:col-span-1">
                    {/* pedido do app: não some da gestão (a cliente continua vendo) e, encerrado, não muda mais */}
                    {!o.shop_cancel && <Button variant="ghost" size="sm" aria-label={`Editar pedido ${o.number}`} onClick={() => edit(g)}><Pencil size={16} /></Button>}
                    {!o.shop_order_id && <Button variant="danger" size="sm" aria-label={`Excluir pedido ${o.number}`} onClick={() => setDel(g)}><Trash2 size={16} /></Button>}
                  </div>
                </motion.li>
              ); })}
            </AnimatePresence>
          </ul>
        </Card>
      )}

      <ShopOrderActions action={shopAct} onClose={() => setShopAct(null)} />

      <Drawer open={!!draft} onClose={() => setDraft(null)} title={draft?.id ? 'Editar pedido' : 'Novo pedido'}
        footer={<><Button variant="ghost" onClick={() => setDraft(null)}>Cancelar</Button><Button type="submit" form="order-form" busy={busy}>Salvar pedido</Button></>}>
        {draft && (
          <form id="order-form" className="grid grid-cols-2 gap-4" onSubmit={(e) => { e.preventDefault(); save(); }}>
            <Field label="Cliente" className="col-span-2">{(id) => <Input maxLength={80} id={id} value={draft.customer} onChange={(e) => setDraft({ ...draft, customer: e.target.value })} />}</Field>
            {draft.shop ? (
              <p className="col-span-2 rounded-2xl bg-surface-2 px-4 py-3 text-sm">
                <b>{draft.items[0].qty}× {draft.items[0].product}</b><br />
                <span className="text-muted">Pedido do app: item e valor não mudam aqui. Para devolver parte do valor, use &quot;Devolver valor&quot;.</span>
              </p>
            ) : (<>
            {draft.items.map((it, i) => (
              <div key={it.id ?? `novo-${i}`} className="col-span-2 grid grid-cols-2 gap-4 rounded-2xl bg-surface-2 p-4">
                {draft.items.length > 1 && (
                  <div className="col-span-2 flex items-center justify-between">
                    <p className="text-sm font-bold">Item {i + 1}</p>
                    <Button type="button" variant="ghost" size="sm" aria-label={`Tirar o item ${i + 1}`} onClick={() => setDraft({ ...draft, items: draft.items.filter((_, k) => k !== i) })}><X size={16} /></Button>
                  </div>
                )}
                <Field label="Receita (opcional)" className="col-span-2" hint="Preenche o produto e o preço sugerido.">{(id) => (
                  <Select id={id} value={it.recipe_id} onChange={(e) => pickRecipe(i, e.target.value)}>
                    <option value="">Produto avulso</option>{recipes.map((r) => <option key={r.id} value={r.id}>{r.name} · {money(r.price)}</option>)}
                  </Select>)}</Field>
                <Field label="Produto" className="col-span-2">{(id) => <Input maxLength={100} id={id} value={it.product} onChange={(e) => setItem(i, { product: e.target.value })} />}</Field>
                <Field label="Quantidade">{(id) => <NumberInput id={id} value={it.qty} onValueChange={(v) => setItem(i, { qty: v })} />}</Field>
                <Field label="Preço unitário (R$)">{(id) => <NumberInput id={id} value={it.unit_price} onValueChange={(v) => setItem(i, { unit_price: v })} />}</Field>
                {draft.items.length > 1 && <p className="tnum col-span-2 text-right text-sm font-semibold text-muted">Subtotal {money(parseNum(it.qty) * parseNum(it.unit_price))}</p>}
              </div>
            ))}
            <Button type="button" variant="soft" className="col-span-2" onClick={() => setDraft({ ...draft, items: [...draft.items, blankItem()] })}><Plus size={18} />Adicionar item</Button>
            <div className="col-span-2 flex items-center justify-between rounded-2xl bg-pink-tint px-4 py-3 text-pink-strong">
              <span className="text-sm font-medium">{draft.items.length > 1 ? `Total do pedido (${draft.items.length} itens)` : 'Total do pedido'}</span>
              <Counter value={draft.items.reduce((sum, it) => sum + parseNum(it.qty) * parseNum(it.unit_price), 0)} className="font-display text-xl font-semibold" />
            </div>
            </>)}
            <Field label="Data do pedido">{(id) => <Input id={id} type="date" value={draft.order_date} onChange={(e) => setDraft({ ...draft, order_date: e.target.value })} />}</Field>
            <Field label="Entrega">{(id) => <Input id={id} type="date" value={draft.delivery_date} onChange={(e) => setDraft({ ...draft, delivery_date: e.target.value })} />}</Field>
            <Field label="Status">{(id) => <Select id={id} value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value })}>{ORDER_STATUS.filter((s) => !draft.shop || s.id !== 'cancelado' || draft.status === 'cancelado').map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}</Select>}</Field>
            <Field label="Pagamento">{(id) => <Select id={id} value={draft.payment} onChange={(e) => setDraft({ ...draft, payment: e.target.value })}>{ORDER_PAYMENT.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}</Select>}</Field>
            <Field label="Observações" className="col-span-2">{(id) => <Textarea maxLength={300} id={id} value={draft.notes} onChange={(e) => setDraft({ ...draft, notes: e.target.value })} />}</Field>
          </form>
        )}
      </Drawer>
      <ConfirmDialog open={!!del} busy={busy} title={del && del.length > 1 ? `Excluir o pedido de ${del[0].customer} (os ${del.length} itens)?` : `Excluir o pedido ${del?.[0].number}?`} message="Ele deixa de contar nas metas do mês. Para manter o histórico, prefira marcar como Cancelado." onClose={() => setDel(null)} onConfirm={remove} />
    </>
  );
}

const FAIL_TEXT: Record<string, string> = { saldo: 'falta saldo no Mercado Pago', prazo: 'o prazo do Pix acabou' };

/** Pedido do app: a devolução mais recente (qualquer estado), o cancelamento/encerramento e os botões, só quando ainda cabem. */
function ShopBands({ o, onAct }: { o: Order; onAct: (mode: 'cancelar' | 'devolver') => void }) {
  const r = o.shop_refund, c = o.shop_cancel;
  const total = o.shop_total ?? 0, refunded = o.shop_refunded ?? 0, left = total - refunded;
  const partial = refunded > 0 && left > 0.004;
  const why = (code?: string | null) => (code ? anyReasonLabel(code) : '');
  const join = (...p: (string | false | null | undefined)[]) => p.filter(Boolean).join(' · ');
  const band: { title: string; line: string; action?: string; tone: string } | null =
    !r ? null
    : r.status === 'solicitado' ? { title: 'Devolução aguardando', line: join(`Pede ${money(r.requested_amount)}`, why(r.reason_code), r.photos > 0 && `${r.photos} foto(s)`), action: 'Ver e decidir', tone: 'warn' }
    : r.status === 'aprovado' || r.status === 'processando' ? { title: 'Devolução em processamento', line: `${money(r.approved_amount ?? 0)} por Pix`, tone: 'pink-strong' }
    : r.status === 'falhou' ? { title: 'Devolução parada', line: `${money(r.approved_amount ?? 0)} · ${FAIL_TEXT[r.fail_reason ?? ''] ?? 'o Mercado Pago não respondeu'}`, action: 'Ver e tentar de novo', tone: 'bad' }
    : r.status === 'concluido' ? { title: partial ? 'Devolução concluída (parcial)' : 'Devolução concluída', line: partial ? `${money(refunded)} de ${money(total)} devolvidos por Pix` : join(`${money(refunded)} devolvidos por Pix`, r.kind === 'solicitado' && why(r.reason_code)), tone: 'ok' }
    : r.status === 'negado' ? { title: 'Devolução negada', line: join(`Pedia ${money(r.requested_amount)}`, why(r.decision_code)), tone: 'muted' }
    : null;
  // devolução feita junto com o cancelamento: a faixa de cancelado já diz quanto voltou
  const showBand = band && !(c && r?.kind !== 'solicitado' && r?.status === 'concluido');
  const canAct = !c && o.status !== 'cancelado' && r?.status !== 'solicitado' && !(o.shop_paid && left <= 0.004);
  const bg: Record<string, string> = { warn: 'bg-warn/15', 'pink-strong': 'bg-pink-tint', bad: 'bg-bad/10', ok: 'bg-ok/10', muted: 'bg-surface-2' };
  const fg: Record<string, string> = { warn: 'text-warn', 'pink-strong': 'text-pink-strong', bad: 'text-bad', ok: 'text-ok', muted: 'text-muted' };
  const body = band && (
    <>
      <span className={`block text-xs font-extrabold uppercase tracking-wide ${fg[band.tone]}`}>{band.title}</span>
      <span className="font-semibold">{band.line}</span>
      {band.action && <span className="block font-bold text-pink-strong">{band.action}</span>}
    </>
  );
  return (
    <>
      {showBand && (band.action
        ? <Link href="/devolucoes" className={`col-span-full rounded-2xl px-4 py-3 text-sm hover:brightness-95 ${bg[band.tone]}`}>{body}</Link>
        : <p className={`col-span-full rounded-2xl px-4 py-3 text-sm ${bg[band.tone]}`}>{body}</p>)}
      {c && (
        <p className="col-span-full rounded-2xl bg-surface-2 px-4 py-3 text-sm">
          {c.code === FULL_REFUND_CODE ? (<><b>Pedido encerrado: </b>todo o valor foi devolvido. A produção parou.</>) : (<>
            <b>{c.by === 'cliente' ? 'Cancelado pela cliente: ' : c.by === 'sistema' ? 'Cancelado automaticamente: ' : 'Cancelado por você: '}</b>{c.reason}{c.note ? ` (${c.note})` : ''}
            {refunded > 0 && <span className="font-bold text-ok"> {money(refunded)} devolvidos.</span>}
          </>)}
        </p>
      )}
      {canAct && (
        <div className="col-span-full flex flex-wrap gap-2">
          {o.status !== 'entregue' && <Button variant="soft" size="sm" onClick={() => onAct('cancelar')}>{!o.shop_paid ? 'Cancelar pedido' : refunded > 0 ? `Cancelar e devolver ${money(left)}` : 'Cancelar e devolver o valor pago'}</Button>}
          {o.shop_paid && <Button variant="ghost" size="sm" onClick={() => onAct('devolver')}>Devolver valor</Button>}
        </div>
      )}
    </>
  );
}

function StateSelect({ value, options, label, onChange, disabled }: { value: string; options: readonly { id: string; label: string; tone: any }[]; label: string; onChange: (v: string) => void; disabled?: boolean }) {
  return (
    <div className="relative inline-flex">
      <Badge tone={toneOf(options, value)}>{options.find((o) => o.id === value)?.label}</Badge>
      <select aria-label={label} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)} className="absolute inset-0 cursor-pointer opacity-0 disabled:cursor-default">
        {options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
      </select>
    </div>
  );
}
