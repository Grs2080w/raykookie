// Lê o tamanho da embalagem no nome de um produto ("Chocolate 1,01kg", "Granulado 400 g", "Leite 1L", "Cx 12 un").
// Devolve sempre na unidade-base do estoque: g, ml ou un.
import type { Unit } from './pricing';

export type Pack = { size: number; unit: Unit };

const num = (s: string) => parseFloat(s.replace(/\./g, (m, i, all) => (all.indexOf(',') >= 0 || /^\d{1,3}(\.\d{3})+$/.test(all.slice(Math.max(0, i - 3))) ? '' : m)).replace(',', '.'));

export function parsePack(name: string): Pack | null {
  const t = name.toLowerCase().replace(/\s+/g, ' ');
  // peso e volume: o último tamanho citado costuma ser o da embalagem ("... 2x500g" vira 1000 g)
  const multi = t.match(/(\d+)\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*(kg|g|gr|ml|l|lt)\b/);
  const re = /(\d+(?:[.,]\d+)?)\s*(kg|g|gr|ml|l|lt)\b/g;
  let m: RegExpExecArray | null, last: RegExpExecArray | null = null;
  while ((m = re.exec(t))) last = m;
  const conv = (v: number, u: string): Pack | null => {
    if (!(v > 0)) return null;
    if (u === 'kg') return { size: v * 1000, unit: 'g' };
    if (u === 'g' || u === 'gr') return { size: v, unit: 'g' };
    if (u === 'l' || u === 'lt') return { size: v * 1000, unit: 'ml' };
    return { size: v, unit: 'ml' };
  };
  if (multi) { const p = conv(num(multi[2]), multi[3]); if (p) return { size: Math.round(p.size * Number(multi[1]) * 1000) / 1000, unit: p.unit }; }
  if (last) { const p = conv(num(last[1]), last[2]); if (p) return { size: Math.round(p.size * 1000) / 1000, unit: p.unit }; }
  const un = t.match(/(?:c\/|cx\.?|caixa|pct\.?|pacote|com|\b)\s*(\d+)\s*(?:un\b|uns\b|unid\w*|pcs|pç\w*|peças)/) ?? t.match(/\b(\d+)\s*(?:un|uns|unid\w*|pcs)\b/);
  if (un && Number(un[1]) > 0) return { size: Number(un[1]), unit: 'un' };
  return null;
}

/** Custo por unidade-base (R$ por g, ml ou un). */
export const pricePerBase = (price: number, size: number) => (size > 0 ? price / size : 0);

/** Preço por kg, L ou unidade, para comparar fornecedores lado a lado. */
export function pricePerLabel(price: number, size: number, unit: Unit): string {
  const p = pricePerBase(price, size);
  const f = (v: number) => v.toFixed(2).replace('.', ',');
  return unit === 'un' ? `R$ ${f(p)}/un` : `R$ ${f(p * 1000)}/${unit === 'g' ? 'kg' : 'L'}`;
}
