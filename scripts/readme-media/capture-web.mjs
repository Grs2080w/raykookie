// Captura as telas do site (build de produção em :10005, API de mentira em :10006).
// Uso: PW_DIR=<pasta com playwright instalado> node scripts/readme-media/capture-web.mjs <pasta-de-saída>
// Cada captura usa só dados fictícios do mock-api.mjs.
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
const login = (ctx) => ctx.addCookies([{ name: 'mock', value: '1', url: BASE }]);

/** Rola a página até o fim para disparar imagens preguiçosas e as revelações por scroll, e volta ao topo. */
async function warm(page) {
  await page.evaluate(async () => {
    for (let y = 0; y < document.body.scrollHeight; y += 500) { window.scrollTo(0, y); await new Promise((r) => setTimeout(r, 120)); }
    window.scrollTo(0, 0);
  });
  await page.waitForTimeout(600);
}

// ── 1. Desktop
{
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'pt-BR' });
  await login(ctx);
  const p = await ctx.newPage();
  const go = async (url, name, opts = {}) => {
    await p.goto(BASE + url, { waitUntil: 'networkidle' });
    await p.waitForTimeout(opts.wait ?? 3800); // o splash do logo leva ~2,5 s
    if (opts.warm) await warm(p);
    await p.screenshot({ path: path.join(OUT, name + '.png'), fullPage: !!opts.full });
    console.log('desktop', name);
  };
  await go('/', 'web-home', { warm: true });
  // rola aos poucos até o cardápio (a roda dispara as revelações por scroll) e espera assentar
  await p.goto(BASE + '/', { waitUntil: 'networkidle' });
  await p.waitForTimeout(4000);
  // alvo: o grupo "Cookies" (três cartões lado a lado) com o título do cardápio ainda à vista
  const top = await p.evaluate(() => {
    const h = [...document.querySelectorAll('#cardapio h3, #cardapio h2')].find((e) => e.textContent.trim() === 'Cookies');
    return h.getBoundingClientRect().top + scrollY - 280;
  });
  await p.mouse.move(720, 450);
  for (let y = 0; y < top; y += 80) { await p.mouse.wheel(0, 80); await p.waitForTimeout(35); }
  await p.waitForTimeout(1800);
  await p.screenshot({ path: path.join(OUT, 'web-cardapio-secao.png') });
  await go('/cardapio', 'web-cardapio', { wait: 2500 });
  // com sessão, /cliente/entrar redireciona para os pedidos: a tela de login é capturada sem o cookie
  await ctx.clearCookies();
  await go('/cliente/entrar', 'web-entrar', { wait: 1200 });
  await login(ctx);
  await go('/cliente/pedidos/novo?item=r1&from=home', 'web-novo-pedido', { wait: 1500 });
  await go('/cliente/pedidos', 'web-pedidos', { wait: 1500 });
  await ctx.close();
}

// ── 2. Celular (mesmo site, viewport de telefone)
{
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'pt-BR' });
  await login(ctx);
  const p = await ctx.newPage();
  const go = async (url, name, opts = {}) => {
    await p.goto(BASE + url, { waitUntil: 'networkidle' });
    await p.waitForTimeout(opts.wait ?? 3800);
    if (opts.warm) await warm(p);
    await p.screenshot({ path: path.join(OUT, name + '.png') });
    console.log('mobile-web', name);
  };
  await go('/', 'webm-home', { warm: true });
  await go('/cliente/pedidos', 'webm-pedidos', { wait: 1500 });
  await ctx.close();
}
await browser.close();
