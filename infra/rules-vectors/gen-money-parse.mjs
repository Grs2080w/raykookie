// Gera backend-java/rules-vectors/money-parse.json a partir de parseLocaleNumber (backend/shared/text.ts).
// Uso único: node --experimental-strip-types infra/rules-vectors/gen-money-parse.mjs
// Cada entrada grava o resultado do TypeScript ({cents} ou {error}); "java":"error" marca as entradas em que
// o Java é deliberadamente mais estrito que o TypeScript (negativos, mais de 2 casas, vazio, texto numérico).
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { parseLocaleNumber } from '../../backend/shared/text.ts';

const inputs = [
  // válidos comuns
  '12,5', '12.5', '1.500,50', '1500', '0', '0,10', '0,20', '0,01', '0.01', '7.5', '12,50', '12.50',
  '1.000', '1.000.000', '12.345', '999.999', '1.000,5', '1.234.567,89', '10', '100,00', '0,5', '0.5', '0.500',
  '1500,5', '1500.50', '  12,5  ', '\u00a012,5', '\uFEFF3,2', '5.', '.5', ',5', '5,', '1.5.5,5', '00012', '012,5',
  '1000000000', '0,00', '9.999.999,99',
  // inválidos nos dois lados
  'abc', '12abc', 'R$ 12,50', '1 000', '1,5,5', '1,2.3', '--5', '1..5', ',', '.', 'NaN', 'null', '1_000', '12,5,', '1.5,5.5x',
  // o Java é mais estrito que o TypeScript
  '', '   ', '-5', '-0,50', '+5', '1,234', '12.3456', '0,001', '1e3', '0x10', 'Infinity', '1.2345,6',
];

const toCents = (n) => (Number.isFinite(n) ? Math.round(n * 100) : null);
const javaStricter = new Set(['', '   ', '-5', '-0,50', '+5', '1,234', '12.3456', '0,001', '1e3', '0x10']);

const rows = inputs.map((input) => {
  const cents = toCents(parseLocaleNumber(input));
  const row = cents === null ? { input, error: true } : { input, cents };
  if (javaStricter.has(input)) row.java = 'error';
  return row;
});

const out = fileURLToPath(new URL('../../backend-java/rules-vectors/money-parse.json', import.meta.url));
writeFileSync(out, JSON.stringify(rows, null, 2) + '\n');
console.log(`${rows.length} entradas -> ${out}`);
