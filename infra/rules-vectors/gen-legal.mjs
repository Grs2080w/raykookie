// Gera backend-java/rules-vectors/legal.json a partir de backend/shared/legal.ts: os Termos e a Política de
// Privacidade exatamente como o Node entrega em GET /api/legal/<documento>. O backend Java empacota este
// arquivo (é a única fonte do texto) e a suíte de contrato compara as respostas com ele.
// Uso: node --experimental-strip-types infra/rules-vectors/gen-legal.mjs (rodar de novo ao mudar o texto).
import { writeFileSync } from 'node:fs';
import { register } from 'node:module';
import { fileURLToPath } from 'node:url';

// shared/legal.ts importa './shop' sem extensão (como o bundler do Next aceita): completa com .ts.
register('data:text/javascript,' + encodeURIComponent(
  'export async function resolve(s, c, next) { try { return await next(s, c); } catch (e) {'
  + " if (s.startsWith('.')) return next(s + '.ts', c); throw e; } }"));
const { LEGAL_DOCS } = await import('../../backend/shared/legal.ts');

const out = fileURLToPath(new URL('../../backend-java/rules-vectors/legal.json', import.meta.url));
writeFileSync(out, JSON.stringify(LEGAL_DOCS, null, 1) + '\n');
console.log(`${Object.keys(LEGAL_DOCS).join(', ')} -> ${out}`);
