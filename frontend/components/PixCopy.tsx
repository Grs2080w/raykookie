'use client';
import { Check, Clock, Copy, RefreshCw } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';

/** "28:41" (ou "1 h 05 min", se faltar mais de uma hora). */
export function formatLeft(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  if (s >= 3600) return `${Math.floor(s / 3600)} h ${String(Math.floor((s % 3600) / 60)).padStart(2, '0')} min`;
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

type Props = { orderId?: number; renewUrl?: string; refundNote?: boolean; code: string; qr: string; expiresIn: number; expired: boolean };

/** Pix copia e cola do pedido, com QR code, contagem regressiva da validade e "Gerar novo Pix" quando vence. */
export function PixCopy({ orderId, renewUrl, refundNote = true, code, qr, expiresIn, expired }: Props) {
  const router = useRouter();
  const [copied, setCopied] = useState(false);
  const [busy, setBusy] = useState(false);
  const [left, setLeft] = useState(expired ? 0 : expiresIn);

  // recomeça a contagem quando chega um Pix novo (o servidor manda os segundos restantes: não depende do relógio do aparelho)
  useEffect(() => {
    if (expired) { setLeft(0); return; }
    const t0 = Date.now();
    setLeft(expiresIn);
    const id = setInterval(() => {
      const l = Math.max(0, expiresIn - Math.floor((Date.now() - t0) / 1000));
      setLeft(l);
      if (l === 0) clearInterval(id);
    }, 1000);
    return () => clearInterval(id);
  }, [expiresIn, expired, code]);

  const gone = expired || left <= 0 || !code;

  async function copy() {
    try { await navigator.clipboard.writeText(code); setCopied(true); setTimeout(() => setCopied(false), 2500); } catch { /* sem permissão de área de transferência */ }
  }
  async function renew() {
    setBusy(true);
    try { await api(renewUrl ?? `/api/cliente/pedidos/${orderId}/pix`, 'POST'); router.refresh(); }
    catch (e: any) { toast.error(e.message); }
    finally { setBusy(false); }
  }

  if (gone) {
    return (
      <div className="mt-3 rounded-2xl border border-line bg-surface-2 p-4" role="status">
        <p className="flex items-center gap-2 font-semibold"><Clock size={18} className="text-muted" />Este Pix expirou</p>
        <p className="mt-1 text-sm text-muted">Por segurança, o código tem prazo de validade. Gere um novo Pix para pagar este pedido.</p>
        <button type="button" onClick={renew} disabled={busy} className="mt-3 inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-pink px-4 font-semibold text-on-pink hover:bg-pink-strong disabled:opacity-60">
          <RefreshCw size={17} className={busy ? 'animate-spin' : ''} />{busy ? 'Gerando…' : 'Gerar novo Pix'}
        </button>
      </div>
    );
  }
  return (
    <div className="mt-3 rounded-2xl border border-pink/40 bg-pink-tint p-4">
      <div className="flex items-start justify-between gap-3">
        <p className="font-semibold text-pink-strong">Pague com Pix</p>
        <p role="timer" aria-label={`O código vale por mais ${formatLeft(left)}`} className={`inline-flex items-center gap-1.5 rounded-full bg-white px-3 py-1 text-sm font-bold tnum ${left < 300 ? 'text-bad' : 'text-ink'}`}>
          <Clock size={14} />Expira em {formatLeft(left)}
        </p>
      </div>
      <p className="mt-1 text-sm text-muted">Assim que o pagamento for confirmado, o pedido é marcado como pago automaticamente.{refundNote && ' Cancelou antes de começarmos a produzir? Devolvemos o Pix na hora.'}</p>
      <div className="mt-3 flex flex-col items-center gap-3 sm:flex-row">
        {qr && /* eslint-disable-next-line @next/next/no-img-element */ <img src={`data:image/png;base64,${qr}`} alt="QR code do Pix" className="size-36 rounded-xl bg-white p-2" />}
        <div className="w-full min-w-0">
          <p className="break-all rounded-xl bg-surface p-3 font-mono text-xs text-muted">{code}</p>
          <button type="button" onClick={copy} className="mt-2 inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-pink px-4 font-semibold text-on-pink hover:bg-pink-strong">
            {copied ? <><Check size={17} />Copiado!</> : <><Copy size={17} />Copiar Pix copia e cola</>}
          </button>
        </div>
      </div>
    </div>
  );
}
