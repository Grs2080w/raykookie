'use client';
import { Copy, Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import clsx from 'clsx';
import { Button, Card, Field, Input, NumberInput, Switch } from '@/components/ui/form';
import { ConfirmDialog, Drawer } from '@/components/ui/Modal';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { money, parseNum, percent } from '@/lib/format';
import type { Coupon } from '@shared/types';

type Draft = { code: string; percent: string; min: string; inclusive: boolean };

/** Regra de valor mínimo em texto. */
function rule(c: Coupon) {
  if (c.min_total == null) return 'Vale para qualquer pedido';
  return c.min_inclusive === false ? `Pedidos acima de ${money(c.min_total)}` : `Pedidos a partir de ${money(c.min_total)}`;
}

/** Cupons de desconto: código (em branco = gerado), 1% a 90% e valor mínimo opcional ("a partir de" ou "acima de"). Toda rota devolve a lista inteira. */
export function CouponsManager({ initial }: { initial: Coupon[] }) {
  const [list, setList] = useState(initial);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [del, setDel] = useState<Coupon | null>(null);
  const [busy, setBusy] = useState(false);

  async function create() {
    if (!draft) return;
    const pct = parseNum(draft.percent);
    if (pct < 1 || pct > 90) return toast.error('O desconto vai de 1% a 90%.');
    setBusy(true);
    try {
      setList(await api<Coupon[]>('/api/coupons', 'POST', { code: draft.code.trim(), percent: pct, min_total: parseNum(draft.min) > 0 ? parseNum(draft.min) : null, min_inclusive: draft.inclusive }));
      toast.ok('Cupom criado');
      setDraft(null);
    } catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function toggle(c: Coupon, active: boolean) {
    try { setList(await api<Coupon[]>(`/api/coupons/${c.id}`, 'PUT', { percent: c.percent, min_total: c.min_total, min_inclusive: c.min_inclusive, active })); }
    catch (e: any) { toast.error(e.message); }
  }
  async function remove() {
    if (!del) return;
    setBusy(true);
    try { setList(await api<Coupon[]>(`/api/coupons/${del.id}`, 'DELETE')); toast.ok('Cupom excluído'); setDel(null); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function copy(code: string) {
    try { await navigator.clipboard.writeText(code); toast.ok('Código copiado'); } catch { toast.error('Não consegui copiar. Selecione o código e copie.'); }
  }

  return (
    <>
      <div className="mb-5"><Button onClick={() => setDraft({ code: '', percent: '', min: '', inclusive: true })}><Plus size={18} />Novo cupom</Button></div>
      {list.length === 0 && <p className="rounded-3xl border border-dashed border-line px-6 py-12 text-center text-muted">Nenhum cupom ainda.</p>}
      <div className="grid gap-3 lg:grid-cols-2">
        {list.map((c) => (
          <Card key={c.id} className="flex flex-col gap-1.5 p-4">
            <div className="flex items-center gap-2">
              <p className="flex-1 select-all font-display text-[22px] font-semibold">{c.code}</p>
              <Button variant="ghost" size="sm" aria-label={`Copiar o código ${c.code}`} onClick={() => copy(c.code)}><Copy size={16} /></Button>
              <Switch checked={c.active} label={`Cupom ${c.code} ativo`} onChange={(v) => toggle(c, v)} />
            </div>
            <p className="font-semibold">-{percent(c.percent)} · {rule(c)}</p>
            <div className="flex items-center justify-between gap-2">
              <p className={clsx('text-[13px]', c.active ? 'text-muted' : 'text-warn')}>{c.active ? 'Ativo' : 'Desativado'} · usado {c.uses} {c.uses === 1 ? 'vez' : 'vezes'}</p>
              {c.uses === 0 && <Button variant="danger" size="sm" onClick={() => setDel(c)}><Trash2 size={15} />Excluir</Button>}
            </div>
          </Card>
        ))}
      </div>

      <Drawer open={!!draft} onClose={() => setDraft(null)} title="Novo cupom"
        footer={<><Button variant="ghost" onClick={() => setDraft(null)}>Cancelar</Button><Button type="submit" form="coupon-form" busy={busy}>Criar</Button></>}>
        {draft && (
          <form id="coupon-form" className="flex flex-col gap-5" onSubmit={(e) => { e.preventDefault(); create(); }}>
            <Field label="Código (opcional)" hint="Em branco, o sistema gera um código.">
              {(id) => <Input id={id} maxLength={20} value={draft.code} onChange={(e) => setDraft({ ...draft, code: e.target.value.toUpperCase() })} placeholder="Ex.: NATAL20" />}
            </Field>
            <Field label="Desconto (%)">{(id) => <NumberInput id={id} integer value={draft.percent} onValueChange={(v) => setDraft({ ...draft, percent: v })} placeholder="Ex.: 15" />}</Field>
            <Field label="Valor mínimo do pedido (R$, opcional)">{(id) => <NumberInput id={id} value={draft.min} onValueChange={(v) => setDraft({ ...draft, min: v })} placeholder="Ex.: 50" />}</Field>
            <div role="group" aria-label="Como o valor mínimo vale" className="inline-flex self-start rounded-2xl border border-line bg-surface p-1">
              {([[true, 'A partir de'], [false, 'Acima de']] as const).map(([v, label]) => (
                <button key={label} type="button" aria-pressed={draft.inclusive === v} onClick={() => setDraft({ ...draft, inclusive: v })}
                  className={clsx('rounded-xl px-4 py-2 text-sm font-semibold transition-colors', draft.inclusive === v ? 'bg-pink text-on-pink' : 'text-muted hover:text-ink')}>{label}</button>
              ))}
            </div>
          </form>
        )}
      </Drawer>

      <ConfirmDialog open={!!del} busy={busy} title={`Excluir o cupom ${del?.code}?`} onClose={() => setDel(null)} onConfirm={remove} message="Quem tiver o código não consegue mais usar." />
    </>
  );
}
