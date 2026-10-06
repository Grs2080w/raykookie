'use client';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Button, Field, Input, NumberInput, Select } from '@/components/ui/form';
import { Drawer } from '@/components/ui/Modal';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { money, parseNum } from '@/lib/format';
import { STORE_CANCEL_REASONS } from '@shared/refund';
import type { Order } from '@shared/types';

export type ShopAction = { order: Order; mode: 'cancelar' | 'devolver' } | null;

/** Cancelar um pedido da loja (devolve o Pix se já estava pago) ou devolver um valor sem cancelar. */
export function ShopOrderActions({ action, onClose }: { action: ShopAction; onClose: () => void }) {
  const router = useRouter();
  const [code, setCode] = useState('');
  const [note, setNote] = useState('');
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const o = action?.order;
  const left = o ? Math.max(0, (o.shop_total ?? 0) - (o.shop_refunded ?? 0)) : 0;

  async function go() {
    if (!o || !action) return;
    setBusy(true);
    try {
      const id = o.shop_order_id;
      const r = action.mode === 'cancelar'
        ? await api<{ refunded: number }>(`/api/orders/shop/${id}/cancelar`, 'POST', { code, note })
        : await api<{ refunded: number }>(`/api/orders/shop/${id}/devolver`, 'POST', { amount: parseNum(amount), note });
      toast.ok(r.refunded ? `Feito. ${money(r.refunded)} devolvidos ao cliente.` : 'Pedido cancelado');
      setCode(''); setNote(''); setAmount(''); onClose(); router.refresh();
    } catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }

  return (
    <Drawer open={!!action} onClose={onClose} title={action?.mode === 'cancelar' ? `Cancelar pedido #${o?.shop_code}` : `Devolver valor · pedido #${o?.shop_code}`}
      footer={<><Button variant="ghost" onClick={onClose}>Voltar</Button><Button variant="danger" busy={busy} onClick={go}>{action?.mode === 'cancelar' ? (o?.shop_paid && left > 0 ? `Cancelar e devolver ${money(left)}` : 'Cancelar pedido') : 'Devolver'}</Button></>}>
      {action && o && (
        <div className="grid gap-4">
          {action.mode === 'cancelar' ? (
            <>
              <p className="text-sm">{o.shop_paid ? <>Já foi pago. Ao cancelar, o valor de <b>{money(left)}</b> volta para a cliente automaticamente, e ela recebe uma notificação com o motivo.</> : 'Ainda não foi pago. A cliente vê o cancelamento e o motivo, e recebe uma notificação no app.'}</p>
              <Field label="Motivo (a cliente vê)">{(id) => (
                <Select id={id} value={code} onChange={(e) => setCode(e.target.value)}>
                  <option value="">Escolha o motivo</option>{STORE_CANCEL_REASONS.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}
                </Select>)}</Field>
              <Field label={code === 'outro' ? 'Conte o motivo (a cliente vê)' : 'Mensagem para a cliente (opcional)'}>{(id) => <Input id={id} maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
            </>
          ) : (
            <>
              <p className="text-sm">Devolve parte do valor por Pix sem cancelar o pedido (por exemplo, se faltou um item). Ainda dá para devolver até <b>{money(left)}</b>.</p>
              <Field label="Valor (R$)">{(id) => <NumberInput id={id} value={amount} onValueChange={setAmount} />}</Field>
              <Field label="Motivo (a cliente vê)">{(id) => <Input id={id} maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
            </>
          )}
        </div>
      )}
    </Drawer>
  );
}
