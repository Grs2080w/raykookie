'use client';
import { CheckCircle2, QrCode } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { PixCopy } from '@/components/PixCopy';
import { api } from '@/lib/client';
import type { PayLinkView } from '@shared/types';

const brl = (n: number) => new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(n);

export function PayLink({ token, view, children }: { token: string; view: PayLinkView; children?: React.ReactNode }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const url = `/api/pagar/${encodeURIComponent(token)}`;
  const waiting = view.state === 'aberto' && !!view.pix;

  // com o Pix na tela, confere o pagamento a cada 5 s e recarrega quando o estado muda
  useEffect(() => {
    if (!waiting) return;
    const id = setInterval(async () => {
      try {
        const r = await fetch(url, { cache: 'no-store' });
        if (r.ok) {
          const data = await r.json();
          // pago/cancelado, ou a confeiteira mudou o valor (o Pix antigo foi cancelado): recarrega a página
          if (data.state !== 'aberto' || data.amount !== view.amount || (data.pix?.code ?? null) !== (view.pix?.code ?? null)) router.refresh();
        }
      } catch { /* sem conexão: tenta de novo no próximo ciclo */ }
    }, 5000);
    return () => clearInterval(id);
  }, [waiting, url, router, view.amount, view.pix?.code]);

  async function start() {
    setBusy(true); setError('');
    try { await api(`${url}/pix`, 'POST'); router.refresh(); }
    catch (e: any) { setError(e.message); }
    finally { setBusy(false); }
  }

  const o = view.order;
  return (
    <>
      {!view.shop_order && <h1 className="font-display text-3xl font-semibold leading-tight">Pedido #{o.number}</h1>}
      {!view.shop_order && <section className="rounded-2xl border border-line bg-surface p-4" aria-label="Resumo do pedido">
        <p className="text-sm text-muted">Para {o.customer}</p>
        <p className="mt-1 font-semibold">{o.qty} × {o.product}</p>
        {o.delivery_date && <p className="text-sm text-muted">Entrega em {o.delivery_date.split('-').reverse().join('/')}</p>}
        <p className="mt-3 text-sm text-muted">Valor a pagar</p>
        <p className="font-display text-3xl font-semibold tnum">{brl(view.amount)}</p>
      </section>}

      {view.state === 'pago' && !view.shop_order && (
        <p role="status" className="flex items-center gap-2 rounded-2xl border border-ok/40 bg-ok/10 p-4 font-semibold text-ok"><CheckCircle2 size={20} />Pagamento confirmado. Obrigada!</p>
      )}
      {view.state === 'cancelado' && (
        <p role="status" className="rounded-2xl border border-line bg-surface-2 p-4 text-muted">Este pedido foi cancelado, então não há nada a pagar. Fale com a gente se precisar.</p>
      )}
      {view.state === 'aberto' && !view.pix && (
        <button type="button" onClick={start} disabled={busy} className="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-pink px-4 font-semibold text-on-pink hover:bg-pink-strong disabled:opacity-60">
          <QrCode size={18} />{busy ? 'Gerando o Pix…' : 'Pagar com Pix'}
        </button>
      )}
      {view.state === 'aberto' && view.pix && (
        <PixCopy renewUrl={`${url}/pix`} refundNote={false} code={view.pix.code} qr={view.pix.qr_base64} expiresIn={view.pix.expires_in} expired={view.pix.expired} />
      )}
      {children}
      {error && <p role="alert" className="text-sm font-semibold text-bad">{error}</p>}
    </>
  );
}
