'use client';
import { ArrowLeft, MapPin, Minus, Plus, UtensilsCrossed } from 'lucide-react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import clsx from 'clsx';
import { EmptyState } from '@/components/PageHeader';
import { Photo } from '@/components/site/Photo';
import { CardFields, type CardFieldsHandle } from '@/components/site/CardFields';
import { Button, Card, Field, Input, NumberInput, Select, Textarea } from '@/components/ui/form';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import type { Customer, Menu } from '@shared/types';
import { money } from '@/lib/format';
import {
  cartTotal, formatCep, formatPhone, FULFILLMENT, LEAD_DAYS, leadDaysLabel, MAX_QTY, onlyDigits, PAYMENT_METHODS, parseShopOrder, photoKind,
  TIME_WINDOWS,
} from '@shared/shop';
import { addDays } from '@shared/text';
import { weightLabel } from '@shared/shop';

function Section({ n, title, hint, children }: { n: number; title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className="mt-7">
      <h2 className="flex items-center gap-2.5 font-display text-xl font-semibold"><span className="grid size-7 place-items-center rounded-full bg-pink text-sm font-bold text-on-pink">{n}</span>{title}</h2>
      {hint && <p className="mt-1 text-sm text-muted">{hint}</p>}
      <div className="mt-3">{children}</div>
    </section>
  );
}

// Opção de escolha única em cartão grande (bom para o toque no celular).
function Choice({ name, value, checked, onChange, title, hint }: { name: string; value: string; checked: boolean; onChange: () => void; title: string; hint?: string }) {
  return (
    <label className="block cursor-pointer">
      <input type="radio" name={name} value={value} checked={checked} onChange={onChange} className="peer sr-only" />
      <span className="flex min-h-14 flex-col justify-center rounded-2xl border-2 border-line bg-surface px-4 py-2.5 transition peer-checked:border-pink peer-checked:bg-pink-tint peer-focus-visible:ring-4 peer-focus-visible:ring-pink/25">
        <span className="font-bold">{title}</span>
        {hint && <span className="text-sm text-muted">{hint}</span>}
      </span>
    </label>
  );
}

export function NewOrderForm({ customer, menu, pickup, initialItem, backHref, minDate }: { customer: Customer; menu: Menu; pickup: { label: string; address: string; instructions: string }; initialItem?: string; backHref?: string; minDate: string }) {
  const router = useRouter();
  const [qty, setQty] = useState<Record<string, number>>(() => (initialItem && menu.items.some((m) => m.id === initialItem) ? { [initialItem]: 1 } : {}));
  const [f, setF] = useState({
    contact_name: customer.name, phone: formatPhone(customer.phone), fulfillment: customer.street ? 'entrega' : 'retirada',
    cep: formatCep(customer.cep), street: customer.street, number: customer.number, complement: customer.complement, district: customer.district, city: customer.city,
    scheduled_for: '', time_window: '', payment_method: '', change_for: '', notes: '',
  });
  const [busy, setBusy] = useState(false);
  const cepAbort = useRef<AbortController | null>(null);
  const set = (patch: Partial<typeof f>) => setF((s) => ({ ...s, ...patch }));
  const [cardCfg, setCardCfg] = useState<{ cardEnabled: boolean; publicKey: string | null } | null>(null);
  const cardRef = useRef<CardFieldsHandle>(null);
  useEffect(() => {
    fetch('/api/payment-config').then((r) => r.json()).then(setCardCfg).catch(() => setCardCfg({ cardEnabled: false, publicKey: null }));
  }, []);
  const paymentMethods = PAYMENT_METHODS.filter((p) => p.id !== 'cartao' || cardCfg?.cardEnabled);

  const lines = Object.entries(qty).filter(([, n]) => n > 0).map(([id, n]) => ({ id, qty: n }));
  const total = cartTotal(lines, menu.items);
  const count = lines.reduce((s, l) => s + l.qty, 0);

  function step(id: string, d: number) {
    setQty((q) => {
      const next = (q[id] ?? 0) + d;
      if (next > MAX_QTY) { toast.warn(`Para mais de ${MAX_QTY} unidades do mesmo item, fale com a gente pelo WhatsApp.`); return q; }
      return { ...q, [id]: Math.max(0, next) };
    });
  }

  function onPhone(v: string) {
    if (/[^\d\s()-]/.test(v)) toast.warn('No telefone só entram números. Ex.: (11) 91234-5678');
    set({ phone: formatPhone(v) });
  }
  async function onCep(v: string) {
    if (/[^\d-]/.test(v)) toast.warn('No CEP só entram números. Ex.: 01310-100');
    const masked = formatCep(v);
    set({ cep: masked });
    const d = onlyDigits(masked, 8);
    if (d.length !== 8) return;
    cepAbort.current?.abort();
    const ctl = (cepAbort.current = new AbortController());
    try {
      const r = await fetch(`https://viacep.com.br/ws/${d}/json/`, { signal: ctl.signal });
      const j = await r.json();
      if (j.erro) { toast.warn('Não encontramos esse CEP. Preencha o endereço na mão.'); return; }
      setF((s) => ({ ...s, street: s.street || j.logradouro || '', district: s.district || j.bairro || '', city: s.city || j.localidade || '' }));
    } catch { /* sem internet ou bloqueado: a pessoa preenche na mão */ }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    let body: Record<string, unknown> = { ...f, items: lines };
    if (f.payment_method === 'cartao') {
      const t = await cardRef.current?.tokenize();
      if (!t) return;
      body = { ...body, card_token: t.card_token, card_payment_method_id: t.card_payment_method_id, cpf: t.cpf, installments: t.installments };
    }
    const check = parseShopOrder(body, addDays(minDate, -LEAD_DAYS), menu.items);
    if (check.error) { toast.error(check.error); return; }
    setBusy(true);
    try {
      const r = await api<{ id: number; card: { approved: boolean; pending?: boolean; reason?: string } | null }>('/api/cliente/pedidos', 'POST', body);
      if (r.card && !r.card.approved && !r.card.pending) toast.error(r.card.reason ?? 'Pagamento recusado. Tente outro cartão no pedido.');
      router.replace(`/cliente/pedidos?novo=${r.id}`); router.refresh();
    } catch (err: any) { toast.error(err.message); setBusy(false); }
  }

  if (menu.items.length === 0) return <EmptyState icon={<UtensilsCrossed size={26} />} title="Cardápio em preparação" text="Ainda não há itens disponíveis para encomenda. Volte em breve!" />;
  return (
    <form id="order-form" onSubmit={submit} noValidate>
      {backHref && <Link href={backHref} className="mb-2 inline-flex min-h-11 items-center gap-1.5 text-sm font-semibold text-muted hover:text-ink"><ArrowLeft size={16} />Voltar ao cardápio</Link>}
      <h1 className="font-display text-3xl font-semibold leading-tight">Novo pedido</h1>
      <p className="text-muted">Monte seu pedido em 5 passos rápidos.</p>

      <Section n={1} title="O que você quer?" hint="Toque em + para adicionar ao pedido.">
        {menu.groups.map((g) => (
          <div key={g.id} className="mb-5">
            {menu.groups.length > 1 && <h3 className="mb-2 text-sm font-bold uppercase tracking-wider text-pink-strong">{g.name}</h3>}
            <ul className="flex flex-col gap-2.5">
              {g.items.map((m) => {
                const n = qty[m.id] ?? 0;
                return (
                  <li key={m.id} className={clsx('flex items-center gap-3 rounded-2xl border-2 bg-surface p-2.5 transition', n > 0 ? 'border-pink' : 'border-line')}>
                    <Photo kind={photoKind(m)} src={m.image} label={m.name} className="size-16 shrink-0" rounded="rounded-xl" />
                    <div className="min-w-0 flex-1">
                      <p className="font-bold leading-snug">{m.name}</p>
                      <p className="tnum text-sm font-semibold">{money(m.price)}{weightLabel(m.weight_g) && <span className="font-normal text-muted"> · {weightLabel(m.weight_g)} cada</span>}</p>
                    </div>
                    <div className="flex items-center gap-1">
                      {n > 0 && (
                        <>
                          <button type="button" onClick={() => step(m.id, -1)} aria-label={`Diminuir ${m.name}`} className="grid size-11 place-items-center rounded-xl border border-line bg-white active:scale-95"><Minus size={18} /></button>
                          <span className="tnum w-7 text-center text-lg font-bold" aria-live="polite">{n}</span>
                        </>
                      )}
                      <button type="button" onClick={() => step(m.id, 1)} aria-label={`Adicionar ${m.name}`} className="grid size-11 place-items-center rounded-xl bg-pink text-on-pink active:scale-95"><Plus size={20} /></button>
                    </div>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </Section>

      <Section n={2} title="Seus dados" hint="O acompanhamento do pedido é feito pelo app. Usamos o telefone só se precisarmos falar com você.">
        <Card className="flex flex-col gap-4 p-4">
          <Field label="Nome completo">{(id) => <Input id={id} autoComplete="name" maxLength={80} value={f.contact_name} onChange={(e) => set({ contact_name: e.target.value })} />}</Field>
          <Field label="Telefone com WhatsApp" hint="Com DDD. Só usamos se for necessário.">{(id) => <Input id={id} type="tel" inputMode="tel" autoComplete="tel-national" maxLength={15} placeholder="(11) 91234-5678" value={f.phone} onChange={(e) => onPhone(e.target.value)} />}</Field>
          <Field label="E-mail">{(id) => <Input id={id} value={customer.email} readOnly disabled />}</Field>
        </Card>
      </Section>

      <Section n={3} title="Como você quer receber?">
        <div className="grid gap-2.5 sm:grid-cols-2">
          {FULFILLMENT.map((o) => <Choice key={o.id} name="fulfillment" value={o.id} checked={f.fulfillment === o.id} onChange={() => set({ fulfillment: o.id })} title={o.label} hint={o.hint} />)}
        </div>
        {f.fulfillment === 'retirada' && pickup.address && (
          <div className="mt-3 flex items-start gap-3 rounded-2xl border border-pink/30 bg-pink-tint p-4" role="note">
            <MapPin size={20} className="mt-0.5 shrink-0 text-pink-strong" />
            <div className="min-w-0 text-sm">
              <p className="font-bold">Endereço de retirada{pickup.label ? `: ${pickup.label}` : ''}</p>
              <p className="mt-0.5 text-[15px]">{pickup.address}</p>
              {pickup.instructions && <p className="mt-0.5 text-muted">{pickup.instructions}</p>}
            </div>
          </div>
        )}
        {f.fulfillment === 'entrega' && (
          <Card className="mt-3 grid grid-cols-6 gap-4 p-4">
            <Field label="CEP" className="col-span-6 sm:col-span-2">{(id) => <Input id={id} inputMode="numeric" autoComplete="postal-code" maxLength={9} placeholder="00000-000" value={f.cep} onChange={(e) => onCep(e.target.value)} />}</Field>
            <Field label="Rua" className="col-span-4 sm:col-span-3">{(id) => <Input id={id} autoComplete="address-line1" maxLength={100} value={f.street} onChange={(e) => set({ street: e.target.value })} />}</Field>
            <Field label="Número" className="col-span-2 sm:col-span-1">{(id) => <Input id={id} maxLength={10} placeholder="S/N" value={f.number} onChange={(e) => set({ number: e.target.value })} />}</Field>
            <Field label="Complemento (opcional)" className="col-span-6 sm:col-span-2">{(id) => <Input id={id} autoComplete="address-line2" maxLength={60} placeholder="Apto, bloco…" value={f.complement} onChange={(e) => set({ complement: e.target.value })} />}</Field>
            <Field label="Bairro" className="col-span-6 sm:col-span-2">{(id) => <Input id={id} maxLength={60} value={f.district} onChange={(e) => set({ district: e.target.value })} />}</Field>
            <Field label="Cidade" className="col-span-6 sm:col-span-2">{(id) => <Input id={id} autoComplete="address-level2" maxLength={60} value={f.city} onChange={(e) => set({ city: e.target.value })} />}</Field>
          </Card>
        )}
        <Card className="mt-3 grid gap-4 p-4 sm:grid-cols-2">
          <Field label={f.fulfillment === 'entrega' ? 'Data da entrega' : 'Data da retirada'} hint={`Encomendas com pelo menos ${leadDaysLabel()}.`}>
            {(id) => <Input id={id} type="date" min={minDate} value={f.scheduled_for} onChange={(e) => set({ scheduled_for: e.target.value })} />}
          </Field>
          <Field label="Período">
            {(id) => (
              <Select id={id} value={f.time_window} onChange={(e) => set({ time_window: e.target.value })}>
                <option value="">Escolha…</option>
                {TIME_WINDOWS.map((w) => <option key={w.id} value={w.id}>{w.label}</option>)}
              </Select>
            )}
          </Field>
        </Card>
      </Section>

      <Section n={4} title="Forma de pagamento" hint="O pagamento é combinado na confirmação do pedido.">
        <div className="grid gap-2.5 sm:grid-cols-3">
          {paymentMethods.map((o) => <Choice key={o.id} name="payment" value={o.id} checked={f.payment_method === o.id} onChange={() => set({ payment_method: o.id })} title={o.label} hint={o.hint} />)}
        </div>
        {f.payment_method === 'cartao' && cardCfg?.publicKey && (
          <Card className="mt-3 p-4"><CardFields ref={cardRef} publicKey={cardCfg.publicKey} amount={total} /></Card>
        )}
        {f.payment_method === 'dinheiro' && (
          <Card className="mt-3 p-4">
            <Field label="Precisa de troco para quanto? (opcional)" hint="Deixe em branco se tiver o valor certinho.">
              {(id) => <NumberInput id={id} placeholder="Ex.: 100" value={f.change_for} onValueChange={(v) => set({ change_for: v })} />}
            </Field>
          </Card>
        )}
      </Section>

      <Section n={5} title="Algo mais?">
        <Field label="Observações (opcional)" hint="Sabor, decoração, alergias, recado no bolo, ponto de referência…">
          {(id) => <Textarea id={id} maxLength={300} value={f.notes} onChange={(e) => set({ notes: e.target.value })} />}
        </Field>
      </Section>

      <p className="mx-1 text-xs text-muted">
        Cancelou antes de começarmos a produzir? Devolvemos o Pix na hora. Depois disso, a devolução depende de análise e não é garantida. <a href="/termos#s6" className="font-semibold text-pink-strong underline">Ver regras</a>.
      </p>

      {/* Barra fixa: total e botão sempre ao alcance do polegar */}
      <div className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-white/95 px-4 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-3 backdrop-blur">
        <div className="mx-auto flex max-w-2xl items-center gap-4">
          <div className="min-w-0">
            <p className="text-xs text-muted">{count === 0 ? 'Nenhum item' : `${count} ${count === 1 ? 'item' : 'itens'}`}</p>
            <p className="font-display text-2xl font-semibold leading-none tnum">{money(total)}</p>
          </div>
          <Button type="submit" busy={busy} className="min-h-12 flex-1 text-base">Finalizar pedido</Button>
        </div>
      </div>
    </form>
  );
}
