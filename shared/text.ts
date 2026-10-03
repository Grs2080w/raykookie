// Utilitários de texto/data puros (sem dependências do servidor), usados na API e na loja.
export const cleanText = (v: unknown) => String(v ?? '').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '').trim();

// "AAAA-MM-DD" que existe no calendário (só a regex aceitaria 2026-02-31 ou 2026-13-01).
export const isoDate = (v: unknown) => {
  const s = cleanText(v);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) return null;
  const d = new Date(`${s}T00:00:00Z`);
  return Number.isNaN(d.getTime()) || d.toISOString().slice(0, 10) !== s ? null : s;
};

export const addDays = (iso: string, n: number) => {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

/**
 * Lê um número digitado em pt-BR:
 *   "1.500,50" → 1500.5 · "1.000" → 1000 (ponto de milhar) · "7.5" → 7.5 · "0.500" → 0.5 · "12,5" → 12.5
 * Ponto seguido de exatamente 3 dígitos, sem vírgula e sem zero à esquerda, é milhar (1 kg = "1.000" g).
 * Resultado NaN quando não é número.
 */
export function parseLocaleNumber(raw: string): number {
  const s = String(raw ?? '').trim();
  if (s.includes(',')) return Number(s.replace(/\./g, '').replace(',', '.'));
  if (/^[1-9]\d{0,2}(\.\d{3})+$/.test(s)) return Number(s.replace(/\./g, ''));
  return Number(s);
}

/** Número do servidor → texto do campo, com vírgula (evita que "2.125" seja lido como milhar ao salvar de novo). */
export const fieldNum = (n: number | string | null | undefined) => (n === null || n === undefined || n === '' ? '' : String(n).replace('.', ','));
