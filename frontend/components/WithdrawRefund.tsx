'use client';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';

export function WithdrawRefund({ orderId, api: refundApi }: { orderId: number; api?: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  async function go() {
    setBusy(true);
    try { await api(refundApi ?? `/api/cliente/pedidos/${orderId}/devolucao`, 'DELETE'); toast.ok('Solicitação cancelada'); router.refresh(); }
    catch (e: any) { toast.error(e.message); router.refresh(); } finally { setBusy(false); }
  }
  return <button type="button" onClick={go} disabled={busy} className="mt-2 min-h-11 text-sm font-semibold text-pink-strong underline disabled:opacity-60">Desistir da solicitação</button>;
}
