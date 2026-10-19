// Segurança do site (Next.js): cabeçalhos em todas as rotas. Rodar: node --test tests/security/
import assert from 'node:assert/strict';
import test from 'node:test';

import config, { securityHeaders } from '../../frontend/next.config.mjs';

const header = (name) => securityHeaders.find((h) => h.key.toLowerCase() === name.toLowerCase())?.value;

test('o site manda cabeçalhos de segurança em todas as rotas', async () => {
  const rules = await config.headers();
  assert.equal(rules.length, 1);
  assert.equal(rules[0].source, '/:path*');
  assert.deepEqual(rules[0].headers, securityHeaders);
});

test('não pode ser embutido em outro site (clickjacking)', () => {
  assert.equal(header('X-Frame-Options'), 'DENY');
  assert.match(header('Content-Security-Policy'), /frame-ancestors 'none'/);
});

test('CSP imposta fecha base-uri, object e form-action', () => {
  const csp = header('Content-Security-Policy');
  for (const d of ["base-uri 'self'", "object-src 'none'", "form-action 'self'"]) assert.ok(csp.includes(d), d);
});

test('nosniff, referrer, permissões e HSTS', () => {
  assert.equal(header('X-Content-Type-Options'), 'nosniff');
  assert.ok(header('Referrer-Policy'));
  assert.match(header('Permissions-Policy'), /camera=\(\)/);
  assert.match(header('Strict-Transport-Security'), /max-age=\d{7,}/);
});

test('CSP completa em modo relatório libera só as origens usadas (Mercado Pago, ViaCEP, Vercel) e nenhum curinga geral', () => {
  const csp = header('Content-Security-Policy-Report-Only');
  assert.match(csp, /default-src 'self'/);
  assert.match(csp, /https:\/\/sdk\.mercadopago\.com/);
  assert.match(csp, /https:\/\/viacep\.com\.br/);
  assert.ok(!/(^|[ ;])\*([ ;]|$)/.test(csp), 'sem * solto');
  assert.ok(!/unsafe-eval/.test(csp));
});
