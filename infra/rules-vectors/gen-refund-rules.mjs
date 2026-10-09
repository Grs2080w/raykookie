// Gera backend-java/rules-vectors/refund-rules.json a partir de backend/shared/refund.ts e shared/shop.ts:
// elegibilidade de devolução, estado do pagamento, situação geral do pedido, rótulos e as listas de motivos.
// Uso: node --experimental-strip-types infra/rules-vectors/gen-refund-rules.mjs
import { writeFileSync } from 'node:fs';
import { register } from 'node:module';
import { fileURLToPath } from 'node:url';

register('data:text/javascript,' + encodeURIComponent(
  'export async function resolve(s, c, next) { try { return await next(s, c); } catch (e) {'
  + " if (s.startsWith('.')) return next(s + '.ts', c); throw e; } }"));
const R = await import('../../backend/shared/refund.ts');
const S = await import('../../backend/shared/shop.ts');

let seed = 20261008;
const rand = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; };
const pick = (l) => l[Math.floor(rand() * l.length)];
const now = new Date('2026-10-08T15:00:00.000Z');
const STATUSES = ['novo', 'producao', 'pronto', 'entregue', 'cancelado'];

const eligibility = [];
for (let i = 0; i < 400; i++) {
  const total = pick([6, 28.5, 98, 150.01, 0.01, 1234.56]);
  const refundedTotal = pick([0, 0, 0, 0.01, total / 2, total, total - 0.01]);
  const paid = rand() > 0.15;
  const paidAt = paid ? new Date(now.getTime() - pick([1, 3600, 86400 * 10, 86400 * 84, 86400 * 86]) * 1000) : null;
  const n = 1 + Math.floor(rand() * 3);
  const itemStatuses = Array.from({ length: n }, () => pick(STATUSES));
  const deliveredAt = rand() > 0.4 ? new Date(now.getTime() - pick([1, 3600 * 47, 3600 * 49, 86400 * 5]) * 1000) : null;
  const liveRequest = rand() > 0.85;
  const input = { paid, paidAt, total, refundedTotal: Math.round(refundedTotal * 100) / 100, itemStatuses, deliveredAt, liveRequest, now };
  eligibility.push({ input: { ...input, paidAt: paidAt?.toISOString() ?? null, deliveredAt: deliveredAt?.toISOString() ?? null, now: now.toISOString() }, output: R.refundEligibility(input) });
}
const paymentStates = [];
for (const paid of [true, false]) for (const cancelled of [true, false]) for (const pixExpired of [true, false])
  for (const [total, refundedTotal] of [[10, 0], [10, 5], [10, 10], [0, 0], [10, 12]])
    paymentStates.push({ input: { paid, cancelled, pixExpired, total, refundedTotal }, output: R.paymentState({ paid, cancelled, pixExpired, total, refundedTotal }) });
const overall = [[], ['novo'], ['pronto', 'producao'], ['entregue', 'cancelado'], ['cancelado'], [null, 'novo'], ['entregue', 'pronto', 'novo']]
  .map((s) => ({ input: s, output: S.overallStatus(s) }));
const labels = ['solicitado', 'desistiu', 'negado', 'aprovado', 'processando', 'concluido', 'falhou'].map((s) => ({ input: s, output: R.refundCustomerLabel(s) }));
const reasons = { CANCEL_REASONS: R.CANCEL_REASONS, REFUND_REASONS: R.REFUND_REASONS, STORE_CANCEL_REASONS: R.STORE_CANCEL_REASONS, DENY_REASONS: R.DENY_REASONS, SYSTEM_REASONS: R.SYSTEM_REASONS };
const out = fileURLToPath(new URL('../../backend-java/rules-vectors/refund-rules.json', import.meta.url));
writeFileSync(out, JSON.stringify({ eligibility, paymentStates, overall, labels, reasons }, null, 1) + '\n');
console.log(`${eligibility.length} elegibilidades -> ${out}`);
