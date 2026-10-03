'use client';
import { AlertTriangle, ImageIcon } from 'lucide-react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Badge } from '@/components/ui/Badge';
import { Button, Card, Field, Input, NumberInput, Select } from '@/components/ui/form';
import { Drawer } from '@/components/ui/Modal';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { money, parseNum } from '@/lib/format';
import { DENY_REASONS, anyReasonLabel } from '@shared/refund';

type Row = { id: number; order_id: number; code: number; customer: string; kind: string; status: string; reason_code: string; priority: boolean; requested_amount: number; approved_amount: number | null; fail_reason: string | null; created_at: string; photos: number };
export type RefundList = { list: Row[]; counts: Record<string, number>; pending_amount: number };
type Detail = Row & {
  phone: string; order_total: number; refunded_total: number; order_items: { name: string; qty: number; unit_price: number }[]; statuses: string[]; delivered_at: string | null;
  description: string; items: { name: string; qty: number }[]; decision_reason: string; decision_note: string; attempts: number; next_try_at: string | null; reason: string;
  photos: { n: number; url: string }[]; events: { at: string; actor: string; type: string; data: any }[];
};

const TABS = [['aguardando', 'Aguardando'], ['andamento', 'Em processamento'], ['concluidas', 'Concluídas'], ['negadas', 'Negadas']] as const;
const KIND: Record<string, string> = { automatico: 'Cancelamento com Pix devolvido', solicitado: 'Pedido de devolução', loja: 'Devolução da loja', sistema: 'Devolução automática' };
const FAIL: Record<string, string> = { saldo: 'Falta saldo no Mercado Pago', prazo: 'Prazo do Pix para devolução vencido', erro: 'Erro ao falar com o Mercado Pago' };
const EVENT: Record<string, string> = {
  criado: 'Pedido criado', pix_gerado: 'Pix gerado', pago: 'Pagamento confirmado', status: 'Status alterado', cancelado: 'Pedido cancelado', devolucao_solicitada: 'Devolução solicitada',
  devolucao_aprovada: 'Devolução aprovada', devolucao_negada: 'Devolução negada', devolucao_enviada: 'Devolução enviada ao Mercado Pago', devolucao_concluida: 'Devolução concluída',
  devolucao_falhou: 'Devolução falhou', devolucao_desistiu: 'Cliente desistiu da solicitação',
};
const when = (iso: string) => new Date(iso).toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
const tone = (s: string) => (s === 'concluido' ? 'ok' : s === 'negado' || s === 'falhou' ? 'bad' : s === 'desistiu' ? 'neutral' : 'warn');

export function RefundsManager({ data, status }: { data: RefundList; status: string }) {
  const router = useRouter();
  const [open, setOpen] = useState<Detail | null>(null);
  const [zoom, setZoom] = useState<string | null>(null);
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const [deny, setDeny] = useState(false);
  const [denyCode, setDenyCode] = useState('');
  const [busy, setBusy] = useState(false);

  async function show(id: number) {
    try {
      const d = await api<Detail>(`/api/devolucoes/${id}`, 'GET');
      setOpen(d); setAmount(String(d.requested_amount).replace('.', ',')); setNote(''); setDeny(false); setDenyCode('');
    } catch (e: any) { toast.error(e.message); }
  }
  const left = open ? Math.max(0, open.order_total - open.refunded_total) : 0;

  async function decide(approve: boolean) {
    if (!open) return;
    setBusy(true);
    try {
      await api(`/api/devolucoes/${open.id}/decisao`, 'POST', approve ? { approve: true, amount: parseNum(amount), note } : { approve: false, code: denyCode, note });
      toast.ok(approve ? 'Aprovada: devolvendo o Pix' : 'Solicitação negada. O cliente vê o motivo.');
      setOpen(null); router.refresh();
    } catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function retry() {
    if (!open) return;
    setBusy(true);
    try { const r = await api<{ status: string }>(`/api/devolucoes/${open.id}/tentar`, 'POST'); toast[r.status === 'concluido' ? 'ok' : 'error'](r.status === 'concluido' ? 'Devolução concluída' : 'Ainda não foi possível. Veja o motivo no detalhe.'); setOpen(null); router.refresh(); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }

  return (
    <>
      {data.pending_amount > 0 && (
        <div role="note" className="mb-4 flex items-start gap-3 rounded-2xl border border-warn/40 bg-warn/10 p-4 text-sm">
          <AlertTriangle size={20} className="mt-0.5 shrink-0" />
          <p>Há <b>{money(data.pending_amount)}</b> em devoluções em processamento. Mantenha esse valor disponível como saldo no Mercado Pago: sem ele a devolução não sai.</p>
        </div>
      )}
      <nav aria-label="Filtro" className="mb-4 flex flex-wrap gap-2">
        {TABS.map(([id, label]) => (
          <Link key={id} href={`/devolucoes?status=${id}`} aria-current={status === id ? 'page' : undefined}
            className={`inline-flex min-h-11 items-center gap-2 rounded-full border px-4 text-sm font-semibold ${status === id ? 'border-ink bg-ink text-white' : 'border-line bg-surface hover:border-ink/30'}`}>
            {label}{data.counts[id] ? <span className="tnum rounded-full bg-pink px-2 text-xs text-on-pink">{data.counts[id]}</span> : null}
          </Link>
        ))}
      </nav>

      {data.list.length === 0 ? <Card className="p-6 text-muted">Nada por aqui.</Card> : (
        <ul className="grid gap-3">
          {data.list.map((r) => (
            <li key={r.id}>
              <button type="button" onClick={() => show(r.id)} className="block w-full text-left">
                <Card className="p-4 transition hover:border-pink">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="font-bold">Pedido #{r.code} · {r.customer}</p>
                    {r.priority && <Badge tone="bad">Prioridade</Badge>}
                    <Badge tone={tone(r.status)}>{r.status === 'solicitado' ? 'Aguardando' : r.status}</Badge>
                    {r.status === 'falhou' && r.fail_reason && <Badge tone="bad">{FAIL[r.fail_reason]}</Badge>}
                  </div>
                  <p className="mt-1 text-sm text-muted">{KIND[r.kind]} · {anyReasonLabel(r.reason_code)}</p>
                  <p className="mt-1 flex flex-wrap items-center gap-x-3 text-sm">
                    <span className="tnum font-semibold">{money(r.approved_amount ?? r.requested_amount)}</span>
                    <span className="text-muted">{when(r.created_at)}</span>
                    {r.photos > 0 && <span className="inline-flex items-center gap-1 text-muted"><ImageIcon size={14} />{r.photos} foto{r.photos > 1 ? 's' : ''}</span>}
                  </p>
                </Card>
              </button>
            </li>
          ))}
        </ul>
      )}

      <Drawer open={!!open} onClose={() => setOpen(null)} title={open ? `Pedido #${open.code}` : ''}
        footer={open && open.status === 'solicitado' ? (deny
          ? <><Button variant="ghost" onClick={() => setDeny(false)}>Voltar</Button><Button variant="danger" busy={busy} onClick={() => decide(false)}>Negar devolução</Button></>
          : <><Button variant="ghost" onClick={() => setDeny(true)}>Negar</Button><Button busy={busy} onClick={() => decide(true)}>{parseNum(amount) >= open.requested_amount ? 'Aprovar total' : 'Aprovar parcial'}</Button></>)
          : open && open.status === 'falhou' ? <Button busy={busy} onClick={retry}>Tentar de novo</Button> : undefined}>
        {open && (
          <div className="grid gap-4 text-sm">
            <div>
              <p className="font-bold">{open.customer} · {open.phone}</p>
              <p className="text-muted">{KIND[open.kind]} · {open.reason}</p>
              <p className="text-muted">Pedido de {money(open.order_total)} · já devolvido {money(open.refunded_total)}{open.delivered_at ? ` · entregue em ${when(open.delivered_at)}` : ''}</p>
            </div>
            {open.status === 'falhou' && <p role="alert" className="rounded-xl bg-bad/10 p-3 text-bad">{open.fail_reason ? FAIL[open.fail_reason] : 'A devolução falhou'}. Tentativas: {open.attempts}.{open.next_try_at ? ` Nova tentativa automática em ${when(open.next_try_at)}.` : ' Toque em "Tentar de novo" depois de resolver.'}</p>}
            {open.description && <p className="rounded-xl bg-surface-2 p-3">{open.description}</p>}
            {open.items.length > 0 && <ul className="list-disc pl-5">{open.items.map((i) => <li key={i.name}>{i.qty}× {i.name}</li>)}</ul>}
            {open.photos.length > 0 && (
              <ul className="flex flex-wrap gap-2" aria-label="Fotos enviadas">
                {open.photos.map((p) => (
                  <li key={p.n}><button type="button" onClick={() => setZoom(p.url)} aria-label={`Ampliar foto ${p.n}`}>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={p.url} alt={`Foto ${p.n} enviada pelo cliente`} className="size-24 rounded-xl border border-line object-cover" />
                  </button></li>
                ))}
              </ul>
            )}
            {open.status === 'solicitado' && !deny && (
              <>
                <Field label={`Valor a devolver (R$) · pediu ${money(open.requested_amount)} · ainda pode ${money(left)}`}>{(id) => <NumberInput id={id} value={amount} onValueChange={setAmount} />}</Field>
                <Field label="Nota para o cliente (obrigatória se devolver menos do que ele pediu)">{(id) => <Input id={id} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />}</Field>
              </>
            )}
            {open.status === 'solicitado' && deny && (
              <>
                <Field label="Motivo da negativa (o cliente vê)">{(id) => (
                  <Select id={id} value={denyCode} onChange={(e) => setDenyCode(e.target.value)}><option value="">Escolha o motivo</option>{DENY_REASONS.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}</Select>)}</Field>
                <Field label="Explique ao cliente">{(id) => <Input id={id} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />}</Field>
              </>
            )}
            {open.status !== 'solicitado' && open.decision_reason && <p>Decisão: {open.decision_reason}. {open.decision_note}</p>}
            <div>
              <p className="font-bold">Histórico do pedido</p>
              <ol className="mt-1 grid gap-1 text-muted">{open.events.map((e, i) => <li key={i}>{when(e.at)} · {EVENT[e.type] ?? e.type} <span className="text-xs">({e.actor})</span></li>)}</ol>
            </div>
          </div>
        )}
      </Drawer>

      {zoom && (
        <div role="dialog" aria-label="Foto ampliada" className="fixed inset-0 z-[100] grid place-items-center bg-black/80 p-4" onClick={() => setZoom(null)}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={zoom} alt="Foto ampliada" className="max-h-full max-w-full rounded-xl" />
        </div>
      )}
    </>
  );
}
