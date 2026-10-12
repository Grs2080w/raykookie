'use client';
import { useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/form';
import { toast } from '@/components/ui/Toast';
import { CardFields, type CardFieldsHandle } from '@/components/site/CardFields';
import { api } from '@/lib/client';
import { useThreeDs } from '@/components/site/ThreeDsDialog';
import { usePaymentConfig } from '@/lib/payment-config';
import { withChallenge, type CardResult } from '@/lib/card-flow';

/** "Tentar outro cartão": aparece no pedido pago com cartão que ainda não foi aprovado (recusado ou pendente). */
export function CardRetry({ total, retryUrl }: { total: number; retryUrl: string }) {
  const router = useRouter();
  const cfg = usePaymentConfig();
  const threeDs = useThreeDs();
  const [busy, setBusy] = useState(false);
  const ref = useRef<CardFieldsHandle>(null);

  if (!cfg?.cardEnabled || !cfg.publicKey) return null;

  async function retry() {
    const t = await ref.current?.tokenize();
    if (!t) return;
    setBusy(true);
    try {
      // o banco pode pedir a confirmação do cartão (3DS): mostra aqui e confere o resultado (GET na mesma rota, sem cobrar de novo)
      const r = await withChallenge(await api<CardResult>(retryUrl, 'POST', t), retryUrl, threeDs.run);
      if (r.approved) { toast.ok('Pagamento aprovado!'); router.refresh(); }
      else if (r.pending) { toast.ok('Pagamento em análise. Avisamos quando confirmar.'); router.refresh(); }
      else toast.error(r.reason ?? 'Pagamento recusado.');
    } catch (e: any) {
      toast.error(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-4 rounded-2xl border border-line bg-surface-2 p-4">
      <p className="mb-3 text-sm font-semibold">Pagamento com cartão ainda não aprovado. Tente outro cartão:</p>
      <CardFields ref={ref} publicKey={cfg.publicKey} amount={total} brands={cfg.brands} />
      <Button type="button" busy={busy} onClick={retry} className="mt-4 w-full">Tentar pagar</Button>
      {threeDs.dialog}
    </div>
  );
}
