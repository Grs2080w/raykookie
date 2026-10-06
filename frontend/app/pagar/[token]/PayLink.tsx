'use client';
import { CheckCircle2, CreditCard, QrCode } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { PixCopy } from '@/components/PixCopy';
import { CardFields, type CardFieldsHandle } from '@/components/site/CardFields';
import { api } from '@/lib/client';
import type { PayLinkView } from '@shared/types';

const brl = (n: number) => new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(n);

export function PayLink({ token, view, children }: { token: string; view: PayLinkView; children?: React.ReactNode }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [method, setMethod] = useState<'' | 'pix' | 'cartao'>('');
  const [cardCfg, setCardCfg] = useState<{ cardEnabled: boolean; publicKey: string | null } | null>(null);
  const cardRef = useRef<CardFieldsHandle>(null);
  const url = `/api/pagar/${encodeURIComponent(token)}`;
  const waiting = view.state === 'aberto' && !!view.pix;

  useEffect(() => {
    fetch('/api/payment-config').then((r) => r.json()).then(setCardCfg).catch(() => setCardCfg({ cardEnabled: false, publicKey: null }));
  }, []);

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

  async function payCard() {
    const t = await cardRef.current?.tokenize();
    if (!t) return;
    setBusy(true); setError('');
    try {
      const r = await api<{ approved: boolean; pending?: boolean; reason?: string }>(`${url}/cartao`, 'POST', t);
      if (r.approved || r.pending) router.refresh();
      else setError(r.reason ?? 'Pagamento recusado. Confira os dados e tente de novo.');
    } catch (e: any) { setError(e.message); }
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
      {view.state === 'aberto' && !view.pix && method === '' && (
        <div className="flex flex-col gap-2.5 sm:flex-row">
          <button type="button" onClick={start} disabled={busy} className="inline-flex min-h-12 flex-1 items-center justify-center gap-2 rounded-xl bg-pink px-4 font-semibold text-on-pink hover:bg-pink-strong disabled:opacity-60">
            <QrCode size={18} />{busy ? 'Gerando o Pix…' : 'Pagar com Pix'}
          </button>
          {cardCfg?.cardEnabled && (
            <button type="button" onClick={() => setMethod('cartao')} className="inline-flex min-h-12 flex-1 items-center justify-center gap-2 rounded-xl border-2 border-line px-4 font-semibold hover:border-pink">
              <CreditCard size={18} />Pagar com cartão
            </button>
          )}
        </div>
      )}
      {view.state === 'aberto' && !view.pix && method === 'cartao' && cardCfg?.publicKey && (
        <div className="rounded-2xl border border-line bg-surface p-4">
          <CardFields ref={cardRef} publicKey={cardCfg.publicKey} amount={view.amount} />
          <button type="button" onClick={payCard} disabled={busy} className="mt-4 inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-pink px-4 font-semibold text-on-pink hover:bg-pink-strong disabled:opacity-60">
            {busy ? 'Enviando…' : 'Pagar com cartão'}
          </button>
          <button type="button" onClick={() => setMethod('')} className="mt-2 w-full text-center text-sm font-semibold text-muted">Voltar</button>
        </div>
      )}
      {view.state === 'aberto' && view.pix && (
        <PixCopy renewUrl={`${url}/pix`} refundNote={false} code={view.pix.code} qr={view.pix.qr_base64} expiresIn={view.pix.expires_in} expired={view.pix.expired} />
      )}
      {view.state === 'aberto' && view.pix && (
        <PixCopy renewUrl={`${url}/pix`} refundNote={false} code={view.pix.code} qr={view.pix.qr_base64} expiresIn={view.pix.expires_in} expired={view.pix.expired} />
      )}
      {children}
      {error && <p role="alert" className="text-sm font-semibold text-bad">{error}</p>}
    </>
  );
}
