'use client';
import { usePathname } from 'next/navigation';
import { useEffect } from 'react';
import { trackScreen } from '@/lib/track';

/** Registra cada tela do site e do cliente (medição anônima; ver `lib/track.ts`). Não desenha nada. */
export function Tracker() {
  const pathname = usePathname();
  useEffect(() => { trackScreen(pathname); }, [pathname]);
  return null;
}
