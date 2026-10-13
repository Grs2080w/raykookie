'use client';
import { useEffect, useRef } from 'react';
import { track } from '@/lib/track';

/**
 * Cartão de um item do cardápio na medição anônima: conta uma vista quando metade do cartão aparece na tela e um
 * clique quando a pessoa toca no link marcado com `data-track-click` (o "Pedir").
 */
export function TrackedItem({ itemId, surface, className, children }: { itemId: string; surface: 'landing' | 'cardapio'; className?: string; children: React.ReactNode }) {
  const ref = useRef<HTMLLIElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof IntersectionObserver === 'undefined') return;
    const seen = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) { track('menu_item_view', { item_id: itemId, detail: surface }); seen.disconnect(); }
    }, { threshold: 0.5 });
    seen.observe(el);
    return () => seen.disconnect();
  }, [itemId, surface]);
  return (
    <li ref={ref} className={className} onClickCapture={(e) => { if ((e.target as HTMLElement).closest('[data-track-click]')) track('menu_item_click', { item_id: itemId, detail: surface }); }}>
      {children}
    </li>
  );
}
