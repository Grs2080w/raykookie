'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ShieldCheck, X } from 'lucide-react';
import { Button } from '@/components/ui/form';

/** Só mensagens que vêm do Mercado Pago valem (o desafio roda numa página dele, dentro do iframe). */
function fromMercadoPago(origin: string): boolean {
  try {
    const h = new URL(origin).hostname;
    return h === 'mercadopago.com.br' || h.endsWith('.mercadopago.com.br') || h === 'mercadopago.com' || h.endsWith('.mercadopago.com');
  } catch { return false; }
}

/** Desafio 3DS do banco dentro da nossa página. `run(url)` abre o desafio e resolve quando o banco avisa que
 *  terminou (mensagem COMPLETE) ou quando a pessoa fecha. Renderize `dialog` no JSX da tela. */
export function useThreeDs() {
  const [url, setUrl] = useState<string | null>(null);
  const resolver = useRef<(() => void) | null>(null);

  const finish = useCallback(() => { resolver.current?.(); resolver.current = null; setUrl(null); }, []);
  const run = useCallback((challengeUrl: string) => new Promise<void>((resolve) => { resolver.current = resolve; setUrl(challengeUrl); }), []);

  useEffect(() => {
    if (!url) return;
    const onMessage = (e: MessageEvent) => { if (fromMercadoPago(e.origin) && e.data?.status === 'COMPLETE') finish(); };
    window.addEventListener('message', onMessage);
    document.body.style.overflow = 'hidden';
    return () => { window.removeEventListener('message', onMessage); document.body.style.overflow = ''; };
  }, [url, finish]);

  const dialog = url ? (
    <div className="fixed inset-0 z-[70] flex items-center justify-center p-3">
      <div className="absolute inset-0 bg-black/55" />
      <div role="dialog" aria-modal="true" aria-label="Confirmação do cartão no seu banco" className="relative flex h-[85dvh] w-full max-w-md flex-col overflow-hidden rounded-3xl border border-line bg-surface shadow-card">
        <header className="flex items-center justify-between gap-2 border-b border-line px-5 py-3">
          <h2 className="flex items-center gap-2 font-display text-lg font-semibold"><ShieldCheck size={18} className="text-ok" />Confirme no seu banco</h2>
          <Button variant="ghost" size="sm" onClick={finish} aria-label="Fechar"><X size={18} /></Button>
        </header>
        <iframe src={url} title="Confirmação do cartão" className="w-full flex-1 bg-white" />
        <footer className="border-t border-line px-5 py-3 text-xs text-muted">Seu banco pede esta confirmação para proteger a compra. Ao terminar, esta janela fecha sozinha.</footer>
      </div>
    </div>
  ) : null;

  return { run, dialog };
}
