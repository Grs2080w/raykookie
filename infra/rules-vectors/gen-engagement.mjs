// Gera backend-java/rules-vectors/engagement.json a partir de shared/engagement.ts: o catálogo da medição de
// engajamento (nomes de evento e valores aceitos). O módulo `engagement` do Java compara o seu catálogo com este
// arquivo, e o app (mobile-common) também; mudou o catálogo? Rode de novo e ajuste os três lados.
// Uso: node --experimental-strip-types infra/rules-vectors/gen-engagement.mjs
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const { ENGAGEMENT_EVENTS } = await import('../../shared/engagement.ts');

const events = Object.fromEntries(Object.entries(ENGAGEMENT_EVENTS).sort(([a], [b]) => a.localeCompare(b)).map(([name, rule]) => [
  name, { route: rule.route, item: rule.item, qty: rule.qty, detail_required: rule.detailRequired, details: [...rule.details].sort() },
]));

const out = fileURLToPath(new URL('../../backend-java/rules-vectors/engagement.json', import.meta.url));
writeFileSync(out, JSON.stringify(events, null, 1) + '\n');
console.log(`${Object.keys(events).length} eventos -> ${out}`);
