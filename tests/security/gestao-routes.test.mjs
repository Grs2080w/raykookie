// Toda aba do menu do painel (frontend/lib/nav.ts) precisa estar em GESTAO_ROUTES (frontend/middleware.ts).
// Senão, no domínio da gestão, a aba é mandada para o site (parece que "voltou para o início"). Rodar: node --test tests/security/
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = (p) => readFileSync(new URL(`../../${p}`, import.meta.url), 'utf8');

test('toda aba do menu do painel está na guarda de rotas da gestão', () => {
  const guard = read('frontend/middleware.ts').match(/GESTAO_ROUTES = new Set\(\[([\s\S]*?)\]\)/);
  assert.ok(guard, 'não achei GESTAO_ROUTES em frontend/middleware.ts');
  const allowed = new Set([...guard[1].matchAll(/'([^']+)'/g)].map((m) => m[1]));
  const hrefs = [...read('frontend/lib/nav.ts').matchAll(/href: '\/([^'/]+)'/g)].map((m) => m[1]);
  assert.ok(hrefs.length > 10, 'não achei as abas em frontend/lib/nav.ts');
  const missing = hrefs.filter((h) => !allowed.has(h));
  assert.deepEqual(missing, [], `faltam em GESTAO_ROUTES (o domínio da gestão redireciona para o site): ${missing.join(', ')}`);
});
