'use client';
import { Ban } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { money } from '@/lib/format';
import { CANCEL_REASONS } from '@shared/refund';

/** "Cancelar pedido": só aparece enquanto a produção não começou. Pede o motivo (lista) e, se já pagou, avisa que o Pix volta na hora. */
export function CancelOrder({ orderId, code, paid, total, cancelUrl }: { orderId: number; code: number; paid: boolean; total: number; cancelUrl?: string }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [reasonCode, setReasonCode] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const needNote = reasonCode === 'outro';

  async function confirm() {
    if (!reasonCode) { toast.error('Escolha o motivo do cancelamento.'); return; }
    if (needNote && note.trim().length < 3) { toast.error('Conte o motivo do cancelamento.'); return; }
    setBusy(true);
    try {
      const r = await api<{ refunded?: number }>(cancelUrl ?? `/api/cliente/pedidos/${orderId}/cancelar`, 'POST', { reason_code: reasonCode, reason: note });
      toast.ok(r.refunded ? `Pedido cancelado. ${money(r.refunded)} voltam por Pix.` : 'Pedido cancelado');
      router.refresh();
    } catch (e: any) { toast.error(e.message); router.refresh(); }
    finally { setBusy(false); }
  }

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className="mt-3 inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl border-2 border-line bg-white px-4 font-semibold text-bad hover:border-bad/40">
        <Ban size={17} />{paid ? 'Cancelar e receber o Pix de volta' : 'Cancelar pedido'}
      </button>
    );
  }
  return (
    <div role="alertdialog" aria-labelledby={`cancel-t-${orderId}`} className="mt-3 rounded-2xl border border-bad/30 bg-bad/5 p-4">
      <p id={`cancel-t-${orderId}`} className="font-bold">Cancelar o pedido #{code}?</p>
      <p className="mt-1 text-sm text-muted">
        {paid
          ? `Ainda não começamos a produzir. Você recebe ${money(total)} de volta por Pix, na conta que pagou, normalmente em poucos minutos.`
          : 'Ele ainda não entrou em produção. Se houver um Pix pendente, o código será desativado.'}
      </p>
      <fieldset className="mt-3">
        <legend className="text-sm font-semibold">Por que você está cancelando?</legend>
        <div className="mt-1 grid gap-1.5">
          {CANCEL_REASONS.map((r) => (
            <label key={r.id} className={`flex min-h-11 cursor-pointer items-center gap-2 rounded-xl border-2 bg-white px-3 text-sm ${reasonCode === r.id ? 'border-pink' : 'border-line'}`}>
              <input type="radio" name={`cr-${orderId}`} value={r.id} checked={reasonCode === r.id} onChange={() => setReasonCode(r.id)} className="accent-[var(--pink)]" />{r.label}
            </label>
          ))}
        </div>
      </fieldset>
      {needNote && (
        <input value={note} onChange={(e) => setNote(e.target.value)} maxLength={150} placeholder="Conte o motivo" aria-label="Conte o motivo"
          className="mt-2 min-h-12 w-full rounded-xl border border-line bg-white px-3 text-base" />
      )}
      <div className="mt-3 grid grid-cols-2 gap-2">
        <button type="button" onClick={() => setOpen(false)} disabled={busy} className="min-h-12 rounded-xl border-2 border-line bg-white font-semibold">Voltar</button>
        <button type="button" onClick={confirm} disabled={busy} className="min-h-12 rounded-xl bg-bad font-semibold text-white disabled:opacity-60">{busy ? 'Cancelando…' : 'Cancelar pedido'}</button>
      </div>
    </div>
  );
}
