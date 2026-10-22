// Captura as telas do app do cliente (Flutter web, build em :10007) contra a API de mentira (:10006): dados 100% fictícios.
// Faz o login pela própria interface, tira as capturas e grava quadros (screencast) das interações para virar GIF.
// Uso: PW_DIR=<pasta com playwright> node scripts/readme-media/capture-app.mjs <pasta-de-saída>
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(path.join(process.env.PW_DIR, 'x.js'));
const { chromium } = require('playwright');
const OUT = path.resolve(process.argv[2]);
const APP = 'http://127.0.0.1:10007/';
fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({
  executablePath: process.env.CHROME || path.join(os.homedir(), '.cache/ms-playwright/chromium-1243/chrome-linux64/chrome'),
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'],
});
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'pt-BR' });
const page = await ctx.newPage();
const shot = (name) => page.screenshot({ path: path.join(OUT, name + '.png') });
const route = async (hash, wait = 2200) => { await page.goto(APP + '#' + hash); await page.waitForTimeout(wait); };
const scrollBy = async (dy, steps = 14, pause = 40) => { // rolagem suave: vários passos curtos da roda
  for (let i = 0; i < steps; i++) { await page.mouse.wheel(0, dy / steps); await page.waitForTimeout(pause); }
};

// ── 1. Abertura e telas sem login
await page.goto(APP, { waitUntil: 'networkidle' });
await page.waitForTimeout(5000);
await shot('app-home');

// ── 2. Login pela interface (campos do Flutter: toque e digitação)
await route('/cliente/entrar');
await shot('app-entrar');
// o Flutter web só cria o <input> escondido depois do toque: espera antes de digitar
await page.mouse.click(195, 457); await page.waitForTimeout(700);
await page.keyboard.type('maria.exemplo@example.com', { delay: 15 });
await page.mouse.click(195, 545); await page.waitForTimeout(700);
await page.keyboard.type('senha-de-exemplo', { delay: 15 });
await shot('app-entrar-preenchido');
await page.mouse.click(195, 609);
await page.waitForTimeout(3500);
await shot('app-pedidos');

// ── 3. Novo pedido, aberto pelo botão da tela de pedidos (assim a barra do topo já tem o botão voltar)
await page.mouse.click(195, 811); await page.waitForTimeout(2500);
await page.mouse.click(336, 521); await page.waitForTimeout(300); // + no primeiro cookie
await page.mouse.move(8, 300); await page.waitForTimeout(900);    // afasta o ponteiro: no celular não existe a dica de hover
await shot('app-novo-pedido');
await scrollBy(560, 10);
await page.waitForTimeout(500);
await shot('app-novo-pedido-2');

// ── 4. Cardápio na home, em seções
await route('/', 3500);
for (let i = 1; i <= 10; i++) { await scrollBy(640, 8); await page.waitForTimeout(700); await shot(`app-home-rolagem-${i}`); }
await browser.close();
console.log('ok');
