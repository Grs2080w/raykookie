// Captura as telas do app de gestão (Flutter web, build em :10007) contra a API de mentira (:10006): dados 100% fictícios.
// Faz o login pela própria interface (usuário e senha de mentira) e depois abre cada tela pela rota.
// Uso: PW_DIR=<pasta com playwright> node scripts/readme-media/capture-gestao-app.mjs <pasta-de-saída>
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(path.join(process.env.PW_DIR, 'x.js'));
const { chromium } = require('playwright');
const OUT = path.resolve(process.argv[2]);
const APP = 'http://127.0.0.1:10007/';
fs.mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({
  executablePath: process.env.CHROME || path.join(os.homedir(), '.cache/ms-playwright/chromium-1243/chrome-linux64/chrome'),
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'],
});
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'pt-BR' });
const page = await ctx.newPage();
const shot = (name) => page.screenshot({ path: path.join(OUT, name + '.png') });
const route = async (hash, wait = 2600) => { await page.goto(APP + '#' + hash); await page.waitForTimeout(wait); };
const scrollBy = async (dy, steps = 12, pause = 40) => { // rolagem suave: vários passos curtos da roda
  for (let i = 0; i < steps; i++) { await page.mouse.wheel(0, dy / steps); await page.waitForTimeout(pause); }
};

// ── 1. Tela de entrada e login pela interface
await page.goto(APP + '#/painel/entrar', { waitUntil: 'networkidle' });
await page.waitForTimeout(5000);
await shot('gapp-entrar');
// o Flutter web só cria o <input> escondido depois do toque: espera antes de digitar
await page.mouse.click(195, 330); await page.waitForTimeout(700);
await page.keyboard.type('rayka', { delay: 15 });
await page.mouse.click(195, 420); await page.waitForTimeout(700);
await page.keyboard.type('senha-de-exemplo', { delay: 15 });
await page.mouse.click(195, 488);
await page.waitForTimeout(4000);
await page.mouse.move(8, 300); await page.waitForTimeout(600);
await shot('gapp-inicio');
await scrollBy(560); await page.waitForTimeout(600);
await shot('gapp-inicio-2');

// ── 2. As cinco abas
await route('/painel/estoque'); await shot('gapp-estoque');
await route('/painel/receitas'); await shot('gapp-receitas');
await route('/painel/pedidos'); await shot('gapp-pedidos');
await route('/painel/mais'); await shot('gapp-mais');

// ── 3. Telas cheias
await route('/painel/receitas/1', 3200); await shot('gapp-receita');
await scrollBy(700); await page.waitForTimeout(600); await shot('gapp-receita-2');
await route('/painel/metas'); await shot('gapp-metas');
await route('/painel/custos'); await shot('gapp-custos');
await route('/painel/cardapio'); await shot('gapp-cardapio');
await route('/painel/cupons'); await shot('gapp-cupons');
await browser.close();
console.log('ok');
