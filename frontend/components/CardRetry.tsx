'use client';
import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/form';
import { toast } from '@/components/ui/Toast';
import { CardFields, type CardFieldsHandle } from '@/components/site/CardFields';
import { api } from '@/lib/client';

/** "Tentar outro cartão": aparece no pedido pago com cartão que ainda não foi aprovado (recusado ou pendente). */
export function CardRetry({ total, retryUrl }: { total: number; retryUrl: string }) {
  const router = useRouter();
  const [cfg, setCfg] = useState<{ cardEnabled: boolean; publicKey: string | null } | null>(null);
  const [busy, setBusy] = useState(false);
  const ref = useRef<CardFieldsHandle>(null);

  useEffect(() => {
    fetch('/api/payment-config').then((r) => r.json()).then(setCfg).catch(() => setCfg({ cardEnabled: false, publicKey: null }));
  }, []);

  if (!cfg?.cardEnabled || !cfg.publicKey) return null;

  async function retry() {
    const t = await ref.current?.tokenize();
    if (!t) return;
    setBusy(true);
    try {
      const r = await api<{ approved: boolean; pending?: boolean; reason?: string }>(retryUrl, 'POST', t);
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
      <CardFields ref={ref} publicKey={cfg.publicKey} amount={total} />
      <Button type="button" busy={busy} onClick={retry} className="mt-4 w-full">Tentar pagar</Button>
    </div>
  );
}
