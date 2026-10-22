// Captura as telas do painel web da confeiteira (build de produção em :10005, API de mentira em :10006).
// Uso: PW_DIR=<pasta com playwright> node scripts/readme-media/capture-gestao-web.mjs <pasta-de-saída>
// Todas as telas usam só os dados fictícios de mock-gestao.mjs.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(path.join(process.env.PW_DIR, 'x.js'));
const { chromium } = require('playwright');
const OUT = path.resolve(process.argv[2]);
const BASE = 'http://localhost:10005';
fs.mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({
  executablePath: process.env.CHROME || path.join(os.homedir(), '.cache/ms-playwright/chromium-1243/chrome-linux64/chrome'),
});
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'pt-BR' });
const p = await ctx.newPage();
const go = async (url, name, wait = 1800) => {
  await p.goto(BASE + url, { waitUntil: 'networkidle' });
  await p.waitForTimeout(wait);
  await p.screenshot({ path: path.join(OUT, name + '.png') });
  console.log('gestao-web', name);
};

// ── 1. A tela de entrada é a única sem sessão
await go('/login', 'gweb-entrar', 1200);

// ── 2. Telas do painel, com a sessão da API de mentira
await ctx.addCookies([{ name: 'mock', value: '1', url: BASE }]);
await go('/dashboard', 'gweb-inicio');
await go('/estoque', 'gweb-estoque');
await go('/receitas', 'gweb-receitas');
await go('/pedidos', 'gweb-pedidos');
await go('/metas', 'gweb-metas');
await go('/configuracoes', 'gweb-custos');
await go('/cardapio-do-site', 'gweb-cardapio');
await go('/cupons', 'gweb-cupons');
await browser.close();
