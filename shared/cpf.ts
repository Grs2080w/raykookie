// CPF: só para repassar ao Mercado Pago no pagamento com cartão (exigência dele para prevenção a fraude).
// Nunca é guardado no nosso banco — ver shared/legal.ts.
export const cleanCpf = (v: unknown): string => String(v ?? '').replace(/\D/g, '');

function checkDigit(digits: string, factor: number): number {
  let sum = 0;
  for (const d of digits) sum += Number(d) * factor--;
  const r = (sum * 10) % 11;
  return r === 10 ? 0 : r;
}

/** Algoritmo padrão de dígito verificador do CPF. Rejeita sequências repetidas (ex.: 111.111.111-11). */
export function isValidCpf(v: unknown): boolean {
  const s = cleanCpf(v);
  if (s.length !== 11 || /^(\d)\1{10}$/.test(s)) return false;
  return checkDigit(s.slice(0, 9), 10) === Number(s[9]) && checkDigit(s.slice(0, 10), 11) === Number(s[10]);
}

export const formatCpf = (v: unknown): string => {
  const s = cleanCpf(v).slice(0, 11);
  return s.replace(/(\d{3})(\d)/, '$1.$2').replace(/(\d{3})(\d)/, '$1.$2').replace(/(\d{3})(\d{1,2})$/, '$1-$2');
};
