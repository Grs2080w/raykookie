'use client';
import { Share2 } from 'lucide-react';
import { toast } from '@/components/ui/Toast';

/** Link da página só com o cardápio: quem recebe vê os itens e, ao pedir, entra na conta e cai no novo pedido. */
export const menuUrl = () => `${window.location.origin}/cardapio`;

/** "Compartilhar cardápio": folha de compartilhar do aparelho; sem ela, copia o link. */
export function ShareMenu({ className, label = 'Compartilhar cardápio' }: { className?: string; label?: string }) {
  async function share() {
    const url = menuUrl();
    try {
      if (navigator.share) { await navigator.share({ title: 'Cardápio Raykookie', text: 'Confira o cardápio da Raykookie:', url }); return; }
    } catch (e: any) { if (e?.name === 'AbortError') return; /* fechou a folha: não é erro */ }
    try { await navigator.clipboard.writeText(url); toast.ok('Link do cardápio copiado'); }
    catch { toast.warn(`Copie o link: ${url}`); }
  }
  return (
    <button type="button" onClick={share} className={className ?? 'inline-flex min-h-11 items-center gap-2 rounded-xl border-2 border-line bg-surface px-4 text-sm font-bold text-ink hover:border-pink'}>
      <Share2 size={17} />{label}
    </button>
  );
}
