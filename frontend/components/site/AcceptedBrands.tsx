import type { CardBrand } from '@/lib/payment-config';

const GROUPS: { type: CardBrand['type']; label: string }[] = [
  { type: 'credit_card', label: 'Crédito' },
  { type: 'debit_card', label: 'Débito' },
  { type: 'prepaid_card', label: 'Pré-pago' },
];

/** Bandeiras que o Mercado Pago aceita nesta loja, por tipo de cartão (logos do próprio Mercado Pago). Sem
 *  bandeiras (consulta falhou), não mostra nada: nunca inventamos uma lista. */
export function AcceptedBrands({ brands }: { brands: CardBrand[] }) {
  if (brands.length === 0) return null;
  return (
    <div className="flex flex-col gap-2 rounded-xl bg-surface-2 p-3" aria-label="Cartões aceitos">
      {GROUPS.map(({ type, label }) => {
        // a mesma bandeira pode vir repetida (ex.: logo igual em crédito e pré-pago): uma vez por tipo
        const list = brands.filter((b) => b.type === type).filter((b, i, all) => all.findIndex((x) => x.logo === b.logo) === i);
        if (list.length === 0) return null;
        return (
          <div key={type} className="flex items-center gap-2">
            <span className="w-16 shrink-0 text-xs font-semibold text-muted">{label}</span>
            <ul className="flex flex-wrap items-center gap-2">
              {list.map((b) => (
                <li key={`${type}-${b.id}`}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={b.logo} alt={b.name} title={b.name} loading="lazy" className="h-6 w-auto max-w-[56px] object-contain" />
                </li>
              ))}
            </ul>
          </div>
        );
      })}
    </div>
  );
}
