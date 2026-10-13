'use client';
import { useEffect, useState } from 'react';
import { isOptedOut, setOptOut, trackingConfigured } from '@/lib/track';

/** "Não medir meu uso": a pessoa desliga (ou volta a ligar) a medição anônima neste aparelho. Some se a medição está desligada. */
export function TrackingToggle({ className }: { className?: string }) {
  const [off, setOff] = useState<boolean | null>(null);
  useEffect(() => { setOff(isOptedOut()); }, []);
  if (!trackingConfigured() || off === null) return null;
  return (
    <button type="button" className={className} onClick={() => { setOptOut(!off); setOff(!off); }}>
      {off ? 'Voltar a medir meu uso' : 'Não medir meu uso'}
    </button>
  );
}
