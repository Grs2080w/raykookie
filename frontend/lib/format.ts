import { fieldNum, parseLocaleNumber } from '@shared/text';
const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const brl4 = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', minimumFractionDigits: 2, maximumFractionDigits: 4 });
const num = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 3 });
export const money = (v: number) => brl.format(v || 0);
export const moneyPrecise = (v: number) => brl4.format(v || 0);
export const number = (v: number) => num.format(v || 0);
export const percent = (v: number) => `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 }).format(v || 0)}%`;
export function dateBR(d: string | Date | null | undefined) {
  if (!d) return '—';
  const s = typeof d === 'string' ? d.slice(0, 10) : d.toISOString().slice(0, 10);
  const [y, m, day] = s.split('-');
  return `${day}/${m}/${y}`;
}
export const monthKey = (d = new Date()) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
// Lê o que a pessoa digitou ("12,5", "12.5", "1.500,50"); inválido vale 0 (só para pré-visualizar).
export const parseNum = (v: string) => {
  const n = parseLocaleNumber(v);
  return Number.isFinite(n) ? n : 0;
};
export { fieldNum };

// Deixa passar só o que faz sentido num campo numérico: dígitos, "," (decimal, uma só) e "." (milhar).
// Com `integer`, só dígitos. Depois da vírgula não entra ponto.
export function sanitizeNumber(raw: string, integer = false) {
  let out = String(raw ?? '').replace(integer ? /[^\d]/g : /[^\d.,]/g, '').slice(0, 15);
  if (!integer) {
    const i = out.indexOf(',');
    if (i >= 0) out = out.slice(0, i + 1) + out.slice(i + 1).replace(/[.,]/g, '');
  }
  return out;
}
// Percentual com 2 casas (1.234,56%), como a tabela da Prospecção mostra.
const pct2 = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
export const percent2 = (v: number) => `${pct2.format(Number.isFinite(v) ? v : 0)}%`;
