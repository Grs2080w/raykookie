import { Check, X } from 'lucide-react';

const STEPS = [
  { id: 'novo', label: 'Recebido' },
  { id: 'producao', label: 'Em preparo' },
  { id: 'pronto', label: 'Pronto' },
  { id: 'entregue', label: 'Entregue' },
];

/** Linha do tempo do pedido para o cliente (status geral: novo → producao → pronto → entregue, ou cancelado). */
export function OrderTimeline({ status }: { status: string }) {
  if (status === 'cancelado') {
    return <p className="flex items-center gap-2 text-sm font-semibold text-bad"><X size={16} />Pedido cancelado</p>;
  }
  const at = Math.max(0, STEPS.findIndex((s) => s.id === status));
  return (
    <ol className="grid grid-cols-4 gap-1" aria-label="Andamento do pedido">
      {STEPS.map((s, i) => {
        const done = i < at || status === 'entregue', current = i === at && status !== 'entregue';
        return (
          <li key={s.id} className="relative flex flex-col items-center gap-1.5 text-center" aria-current={current ? 'step' : undefined}>
            {i > 0 && <span aria-hidden className={`absolute right-1/2 top-3 h-0.5 w-full ${i <= at ? 'bg-pink' : 'bg-line'}`} />}
            <span className={`relative z-10 grid size-6 place-items-center rounded-full border-2 ${done ? 'border-pink bg-pink text-on-pink' : current ? 'border-pink bg-surface' : 'border-line bg-surface'}`}>
              {done ? <Check size={14} strokeWidth={3} /> : current ? <span className="size-2 rounded-full bg-pink" /> : null}
            </span>
            <span className={`text-xs leading-tight ${done || current ? 'font-semibold text-ink' : 'text-muted'}`}>{s.label}</span>
          </li>
        );
      })}
    </ol>
  );
}
