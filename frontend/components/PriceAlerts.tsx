'use client';
import { TrendingDown } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Button, Card } from '@/components/ui/form';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { money } from '@/lib/format';

export type PriceAlert = { id: number; recipe_id: number; recipe: string; kind: 'margem' | 'preco'; before_value: number; after_value: number; suggested: number | null; cause: string };
const pct = (n: number) => `${String(Math.round(n * 10) / 10).replace('.', ',')}%`;

/** Alertas de margem/preço depois de um ingrediente mudar de preço. "Aplicar" fixa o preço do cardápio no valor sugerido. */
export function PriceAlerts({ alerts }: { alerts: PriceAlert[] }) {
  const router = useRouter();
  const [busy, setBusy] = useState<number | null>(null);
  if (!alerts.length) return null;
  async function act(a: PriceAlert, apply: boolean) {
    setBusy(a.id);
    try {
      await api(`/api/price-alerts/${a.id}${apply ? '/apply' : ''}`, 'POST');
      toast.ok(apply ? `Preço de ${a.recipe} fixado em ${money(a.suggested ?? 0)}` : 'Alerta dispensado');
      router.refresh();
    } catch (e: any) { toast.error(e.message); } finally { setBusy(null); }
  }
  return (
    <Card className="mb-6 border-warn/40 bg-warn/5 p-5" role="region" aria-label="Alertas de preço">
      <h2 className="flex items-center gap-2 font-display text-xl font-semibold"><TrendingDown size={20} className="text-warn" />Ingredientes mudaram de preço</h2>
      <ul className="mt-3 divide-y divide-line">
        {alerts.map((a) => (
          <li key={a.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
            <div className="min-w-0">
              <p className="font-semibold">{a.recipe}: {a.kind === 'margem' ? `margem caiu de ${pct(a.before_value)} para ${pct(a.after_value)}` : `preço sugerido sobe de ${money(a.before_value)} para ${money(a.after_value)}`}</p>
              <p className="text-sm text-muted">{a.cause}</p>
            </div>
            <div className="flex gap-2">
              {a.suggested ? <Button size="sm" busy={busy === a.id} onClick={() => act(a, true)}>Aplicar {money(a.suggested)}</Button> : null}
              <Button size="sm" variant="ghost" disabled={busy === a.id} onClick={() => act(a, false)}>Dispensar</Button>
            </div>
          </li>
        ))}
      </ul>
    </Card>
  );
}
