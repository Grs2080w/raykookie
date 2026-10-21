// Grava a animação do logo da home: quadros do screencast do Chrome (PNG + timestamp) para montar o vídeo com o ffmpeg.
// A repetição que dispara o hover do componente LogoIntro começa e termina no logo estático, então o loop é contínuo.
// Uso: PW_DIR=<pasta com playwright> node scripts/readme-media/capture-hero.mjs <pasta-de-saída> [viewport-largura] [viewport-altura]
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(path.join(process.env.PW_DIR, 'x.js'));
const { chromium } = require('playwright');
const OUT = path.resolve(process.argv[2]);
const W = Number(process.argv[3] || 1440), H = Number(process.argv[4] || 760);
const BASE = 'http://localhost:10005';
fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({
  executablePath: process.env.CHROME || path.join(os.homedir(), '.cache/ms-playwright/chromium-1243/chrome-linux64/chrome'),
});
const ctx = await browser.newContext({ viewport: { width: W, height: H }, locale: 'pt-BR' });
const page = await ctx.newPage();
await page.goto(BASE + '/', { waitUntil: 'networkidle' });
await page.waitForTimeout(4500); // a abertura termina e o logo assenta
const box = await page.locator('.rk-hero__logo').boundingBox();

const cdp = await ctx.newCDPSession(page);
const frames = [];
let n = 0;
cdp.on('Page.screencastFrame', async ({ data, metadata, sessionId }) => {
  const file = path.join(OUT, `f${String(n++).padStart(5, '0')}.png`);
  fs.writeFileSync(file, Buffer.from(data, 'base64'));
  frames.push({ file, ts: metadata.timestamp });
  await cdp.send('Page.screencastFrameAck', { sessionId }).catch(() => {});
});
await cdp.send('Page.startScreencast', { format: 'png', everyNthFrame: 1 });

// ponteiro fora → dentro do logo: dispara a repetição ("o biscoito se refaz, treme e leva as mordidas")
await page.mouse.move(5, 5);
await page.waitForTimeout(600);
const mark = Date.now();
await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 2 });
await page.waitForTimeout(4200);
await cdp.send('Page.stopScreencast');
await browser.close();

fs.writeFileSync(path.join(OUT, 'frames.json'), JSON.stringify({ box, frames, mark }));
console.log(frames.length, 'quadros', 'duração', (frames.at(-1).ts - frames[0].ts).toFixed(2), 's');
