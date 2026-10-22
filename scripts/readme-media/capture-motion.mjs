// Grava os clipes de interação (quadros do screencast do Chrome) do site e do app, contra a API de mentira.
// Saída: <pasta>/<clipe>/f00000.png … + frames.json, que o build-clip.mjs transforma em GIF/MP4.
// Uso: PW_DIR=<pasta com playwright> node scripts/readme-media/capture-motion.mjs <pasta-de-saída> [clipe ...]
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(path.join(process.env.PW_DIR, 'x.js'));
const { chromium } = require('playwright');
const OUT = path.resolve(process.argv[2]);
const only = process.argv.slice(3);
const SITE = 'http://localhost:10005', APP = 'http://127.0.0.1:10007/';
fs.mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({
  executablePath: process.env.CHROME || path.join(os.homedir(), '.cache/ms-playwright/chromium-1243/chrome-linux64/chrome'),
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'],
});

/** Grava os quadros que `action` produz; o screencast só emite quando a tela muda. */
async function record(ctx, page, name, action) {
  if (only.length && !only.includes(name)) return;
  const dir = path.join(OUT, name);
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  const cdp = await ctx.newCDPSession(page);
  const frames = [];
  let n = 0;
  cdp.on('Page.screencastFrame', ({ data, metadata, sessionId }) => {
    const file = path.join(dir, `f${String(n++).padStart(5, '0')}.png`);
    fs.writeFileSync(file, Buffer.from(data, 'base64'));
    frames.push({ file, ts: metadata.timestamp });
    cdp.send('Page.screencastFrameAck', { sessionId }).catch(() => {});
  });
  await cdp.send('Page.startScreencast', { format: 'png', everyNthFrame: 1 });
  await page.waitForTimeout(500);
  await action();
  await page.waitForTimeout(500);
  await cdp.send('Page.stopScreencast');
  fs.writeFileSync(path.join(dir, 'frames.json'), JSON.stringify({ frames }));
  console.log(name, frames.length, 'quadros');
}

/** Rolagem suave com a roda do mouse (passos curtos, para o movimento sair fluido no GIF). */
const smooth = async (page, dy, ms = 1600) => {
  const steps = Math.max(8, Math.round(ms / 30));
  for (let i = 0; i < steps; i++) { await page.mouse.wheel(0, dy / steps); await page.waitForTimeout(ms / steps); }
};

// ── Site (desktop, com a sessão fictícia)
{
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 760 }, locale: 'pt-BR' });
  await ctx.addCookies([{ name: 'mock', value: '1', url: SITE }]);
  const page = await ctx.newPage();

  await page.goto(SITE + '/', { waitUntil: 'networkidle' });
  await page.waitForTimeout(4500); // o splash do logo termina e assenta no símbolo
  await record(ctx, page, 'web-rolagem', async () => {
    await page.mouse.move(640, 400);
    await smooth(page, 900, 1400);       // do topo até o cardápio
    await page.waitForTimeout(500);
    await smooth(page, 700, 1200);       // percorre os itens
    await page.waitForTimeout(700);
    await smooth(page, -1600, 1800);     // volta ao topo: o último quadro é o primeiro
  });

  await page.goto(SITE + '/cardapio', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1800);
  await record(ctx, page, 'web-pedido', async () => {
    await smooth(page, 500, 1000);
    await page.waitForTimeout(400);
    await page.getByRole('link', { name: /pedir/i }).first().click();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1500);
    await smooth(page, 500, 1000);
    await page.waitForTimeout(500);
  });
  await ctx.close();
}

// ── App (Flutter web em viewport de celular)
{
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'pt-BR' });
  const page = await ctx.newPage();
  await page.goto(APP, { waitUntil: 'networkidle' });
  await page.waitForTimeout(5000);

  await record(ctx, page, 'app-cardapio', async () => {
    await page.mouse.move(195, 500);
    await smooth(page, 1700, 2000);      // do topo até os primeiros itens do cardápio
    await page.waitForTimeout(600);
    await smooth(page, -1700, 1600);     // volta ao topo
  });

  // login pela interface, para o clipe do pedido
  await page.goto(APP + '#/cliente/entrar'); await page.waitForTimeout(2500);
  await page.mouse.click(195, 457); await page.waitForTimeout(700);
  await page.keyboard.type('maria.exemplo@example.com', { delay: 10 });
  await page.mouse.click(195, 545); await page.waitForTimeout(700);
  await page.keyboard.type('senha-de-exemplo', { delay: 10 });
  await page.mouse.click(195, 609); await page.waitForTimeout(3500);

  await page.waitForTimeout(1500); // o login já leva à tela de pedidos
  await record(ctx, page, 'app-pedido', async () => {
    await page.mouse.click(195, 811); await page.waitForTimeout(1800); // Novo pedido
    await page.mouse.click(336, 327); await page.waitForTimeout(700);   // + no combo
    await page.mouse.click(336, 521); await page.waitForTimeout(700);   // + no primeiro cookie
    await page.mouse.click(336, 521); await page.waitForTimeout(900);
    await page.mouse.click(258, 810); await page.waitForTimeout(1600); // Continuar
    await page.mouse.move(195, 500);
    await smooth(page, 500, 900);
    await page.waitForTimeout(600);
  });
  await ctx.close();
}
await browser.close();
