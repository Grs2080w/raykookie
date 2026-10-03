'use client';
import { MapPin, Pencil, Plus, Trash2 } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import clsx from 'clsx';
import { Badge } from '@/components/ui/Badge';
import { Button, Card, Field, Input } from '@/components/ui/form';
import { ConfirmDialog, Drawer } from '@/components/ui/Modal';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';

export type Pickup = {
  id: number; label: string; cep: string; street: string; number: string; complement: string; district: string; city: string;
  instructions: string; is_active: boolean; address: string;
};
type Draft = Omit<Pickup, 'id' | 'is_active' | 'address'> & { id?: number; active: boolean };

const blank = (first: boolean): Draft => ({ label: '', cep: '', street: '', number: '', complement: '', district: '', city: '', instructions: '', active: first });
const maskCep = (v: string) => { const d = v.replace(/\D/g, '').slice(0, 8); return d.length > 5 ? `${d.slice(0, 5)}-${d.slice(5)}` : d; };

export function PickupManager({ list }: { list: Pickup[] }) {
  const router = useRouter();
  const [draft, setDraft] = useState<Draft | null>(null);
  const [del, setDel] = useState<Pickup | null>(null);
  const [busy, setBusy] = useState(false);

  async function lookup(masked: string) {
    const d = masked.replace(/\D/g, '');
    if (d.length !== 8) return;
    try {
      const r = await (await fetch(`https://viacep.com.br/ws/${d}/json/`)).json();
      if (r.erro) return;
      setDraft((cur) => cur && ({ ...cur, street: cur.street || r.logradouro || '', district: cur.district || r.bairro || '', city: cur.city || r.localidade || '' }));
    } catch { /* sem internet: preenche na mão */ }
  }
  async function save() {
    if (!draft) return;
    setBusy(true);
    try {
      const body = { ...draft, cep: draft.cep.replace(/\D/g, '') };
      if (draft.id) await api(`/api/pickup-addresses/${draft.id}`, 'PATCH', { ...body, ...(draft.active ? { active: true } : {}) });
      else await api('/api/pickup-addresses', 'POST', body);
      toast.ok(draft.id ? 'Endereço atualizado' : 'Endereço adicionado');
      setDraft(null); router.refresh();
    } catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function choose(a: Pickup) {
    if (a.is_active) return;
    try { await api(`/api/pickup-addresses/${a.id}`, 'PATCH', { active: true }); toast.ok('Endereço de retirada atualizado'); router.refresh(); }
    catch (e: any) { toast.error(e.message); }
  }
  async function remove() {
    if (!del) return;
    setBusy(true);
    try { await api(`/api/pickup-addresses/${del.id}`, 'DELETE'); toast.ok('Endereço removido'); setDel(null); router.refresh(); }
    catch (e: any) { toast.error(e.message); setDel(null); } finally { setBusy(false); }
  }

  return (
    <>
      <div className="mb-6"><Button onClick={() => setDraft(blank(list.length === 0))}><Plus size={18} />Adicionar endereço</Button></div>

      {list.length === 0 ? (
        <p className="rounded-3xl border border-dashed border-line px-6 py-12 text-center text-muted">Nenhum endereço de retirada. Adicione onde os clientes vão retirar os pedidos; enquanto não houver um, o cliente vê só “Retirada no local”.</p>
      ) : (
        <ul className="grid gap-3 lg:grid-cols-2" role="radiogroup" aria-label="Endereço de retirada atual">
          {list.map((a) => (
            <li key={a.id}>
              <Card className={clsx('group flex items-start gap-3.5 p-4 transition-colors', a.is_active && 'border-pink bg-pink-tint')}>
                <button type="button" role="radio" aria-checked={a.is_active} onClick={() => choose(a)} aria-label={`Usar ${a.label} como endereço de retirada`}
                  className="mt-0.5 grid size-11 shrink-0 place-items-center rounded-full">
                  <span className={clsx('grid size-5 place-items-center rounded-full border-2', a.is_active ? 'border-pink' : 'border-line')}>{a.is_active && <span className="size-2.5 rounded-full bg-pink" />}</span>
                </button>
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-2 font-semibold"><MapPin size={16} className="text-pink-strong" />{a.label}{a.is_active && <Badge tone="warn" dot={false}>Retirada atual</Badge>}</p>
                  <p className="mt-1 text-[15px]">{a.address}</p>
                  {a.instructions && <p className="mt-0.5 text-sm text-muted">{a.instructions}</p>}
                </div>
                <div className="flex gap-0.5">
                  <Button variant="ghost" size="sm" aria-label={`Editar ${a.label}`} onClick={() => setDraft({ id: a.id, label: a.label, cep: maskCep(a.cep), street: a.street, number: a.number, complement: a.complement, district: a.district, city: a.city, instructions: a.instructions, active: false })}><Pencil size={16} /></Button>
                  <Button variant="danger" size="sm" aria-label={`Excluir ${a.label}`} onClick={() => setDel(a)}><Trash2 size={16} /></Button>
                </div>
              </Card>
            </li>
          ))}
        </ul>
      )}

      <Drawer open={!!draft} onClose={() => setDraft(null)} title={draft?.id ? 'Editar endereço' : 'Adicionar endereço'}
        footer={<><Button variant="ghost" onClick={() => setDraft(null)}>Cancelar</Button><Button type="submit" form="pickup-form" busy={busy}>Salvar endereço</Button></>}>
        {draft && (
          <form id="pickup-form" className="grid grid-cols-6 gap-4" onSubmit={(e) => { e.preventDefault(); save(); }}>
            <Field label="Apelido do endereço" hint="Como você chama este lugar. Só você vê." className="col-span-6">{(id) => <Input id={id} maxLength={40} placeholder="Ex.: Ateliê" value={draft.label} onChange={(e) => setDraft({ ...draft, label: e.target.value })} />}</Field>
            <Field label="CEP" className="col-span-6 sm:col-span-2">{(id) => <Input id={id} inputMode="numeric" maxLength={9} placeholder="00000-000" value={draft.cep} onChange={(e) => { const v = maskCep(e.target.value); setDraft({ ...draft, cep: v }); lookup(v); }} />}</Field>
            <Field label="Rua" className="col-span-4 sm:col-span-3">{(id) => <Input id={id} maxLength={100} required value={draft.street} onChange={(e) => setDraft({ ...draft, street: e.target.value })} />}</Field>
            <Field label="Número" className="col-span-2 sm:col-span-1">{(id) => <Input id={id} maxLength={10} placeholder="S/N" required value={draft.number} onChange={(e) => setDraft({ ...draft, number: e.target.value })} />}</Field>
            <Field label="Complemento (opcional)" className="col-span-6">{(id) => <Input id={id} maxLength={60} placeholder="Sala, bloco…" value={draft.complement} onChange={(e) => setDraft({ ...draft, complement: e.target.value })} />}</Field>
            <Field label="Bairro" className="col-span-6 sm:col-span-3">{(id) => <Input id={id} maxLength={60} required value={draft.district} onChange={(e) => setDraft({ ...draft, district: e.target.value })} />}</Field>
            <Field label="Cidade" className="col-span-6 sm:col-span-3">{(id) => <Input id={id} maxLength={60} required value={draft.city} onChange={(e) => setDraft({ ...draft, city: e.target.value })} />}</Field>
            <Field label="Como chegar (opcional)" hint="O cliente vê junto com o endereço." className="col-span-6">{(id) => <Input id={id} maxLength={200} placeholder="Ex.: portão azul, tocar o interfone 2" value={draft.instructions} onChange={(e) => setDraft({ ...draft, instructions: e.target.value })} />}</Field>
            <label className="col-span-6 flex min-h-11 items-center gap-3 text-sm font-medium">
              <input type="checkbox" className="size-5 accent-[var(--pink)]" checked={draft.active || list.length === 0} disabled={list.length === 0} onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />
              Usar como endereço de retirada{list.length === 0 ? ' (é o primeiro, já fica como o atual)' : ''}
            </label>
          </form>
        )}
      </Drawer>
      <ConfirmDialog open={!!del} title={`Excluir “${del?.label ?? ''}”?`} message="Os pedidos que já foram feitos mantêm o endereço que tinham." busy={busy} onConfirm={remove} onClose={() => setDel(null)} />
    </>
  );
}
