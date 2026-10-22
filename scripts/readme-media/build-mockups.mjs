// Monta os mockups de dispositivo (moldura de celular e de notebook em HTML/CSS) em volta das capturas reais.
// O conteúdo das telas é sempre a captura do app/site de verdade; só a moldura e o fundo são desenhados aqui.
// Uso: PW_DIR=<pasta com playwright> node scripts/readme-media/build-mockups.mjs <pasta-das-capturas> <pasta-de-saída>
//   a pasta de capturas precisa ter (1440x900 para web-*, 780x1688 para app-*):
//   web-home, web-cardapio, web-novo-pedido, web-pedidos, web-entrar  e  app-home, app-cardapio, app-itens, app-novo-pedido, app-pedidos, app-entrar
//   Com o 3º argumento `gestao`, monta os mockups dos apps de gestão (capturas gweb-* e gapp-*, nomes na lista `gestaoJobs`).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(path.join(process.env.PW_DIR, 'x.js'));
const { chromium } = require('playwright');
const SRC = path.resolve(process.argv[2]);
const OUT = path.resolve(process.argv[3]);
const GESTAO = process.argv[4] === 'gestao';
fs.mkdirSync(OUT, { recursive: true });

const url = (name) => 'file://' + path.join(SRC, name + '.png');

const CSS = `
*{box-sizing:border-box;margin:0}
body{background:transparent}
.stage{position:relative;overflow:hidden;border-radius:28px;background:
  radial-gradient(circle at 12% 18%, rgba(233,75,138,.20), transparent 38%),
  radial-gradient(circle at 92% 88%, rgba(233,75,138,.14), transparent 40%),
  linear-gradient(135deg,#fbf3ea,#f1dfcc)}
.phone{position:absolute;background:#17110e;border-radius:15% / 7%;
  box-shadow:0 0 0 2px #3a302b inset,0 30px 60px -20px rgba(58,29,18,.55),0 12px 24px -12px rgba(58,29,18,.4)}
.phone img{display:block;width:100%;border-radius:12.5% / 5.8%}
.phone::before{content:"";position:absolute;left:50%;top:calc(var(--w) * .024);width:calc(var(--w) * .27);height:calc(var(--w) * .05);transform:translateX(-50%);background:#000;border-radius:99px;z-index:2}
.laptop{position:absolute}
.lid{background:#17110e;border-radius:2.2% 2.2% .6% .6% / 3.4% 3.4% .9% .9%;padding:1.5% 1.5% 2.2%;position:relative;box-shadow:0 0 0 2px #3a302b inset}
.lid::before{content:"";position:absolute;left:50%;top:.8%;width:.7%;aspect-ratio:1;transform:translateX(-50%);background:#2c2420;border-radius:50%}
.lid img{display:block;width:100%;border-radius:.4%}
.base{height:2.2%;aspect-ratio:auto;margin:0 -6%;height:26px;background:linear-gradient(#d9d0c8,#a99f97);border-radius:0 0 22px 22px/0 0 14px 14px;position:relative;box-shadow:0 22px 30px -14px rgba(58,29,18,.5)}
.base::after{content:"";position:absolute;left:50%;top:0;width:16%;height:9px;transform:translateX(-50%);background:#8f857d;border-radius:0 0 10px 10px}
`;

/** Celular: moldura em volta da captura, com largura `w` e posição absoluta. */
const phone = (name, x, y, w, rot = 0, z = 1) =>
  `<div class="phone" style="left:${x}px;top:${y}px;width:${w}px;--w:${w}px;padding:calc(var(--w) * .078) calc(var(--w) * .03) calc(var(--w) * .03);transform:rotate(${rot}deg);z-index:${z}"><img src="${url(name)}"></div>`;
/** Notebook: tampa com a captura e a base. */
const laptop = (name, x, y, w, z = 1) =>
  `<div class="laptop" style="left:${x}px;top:${y}px;width:${w}px;z-index:${z}"><div class="lid"><img src="${url(name)}"></div><div class="base"></div></div>`;

const clientJobs = [
  // um celular por tela do app
  ...['home', 'cardapio', 'itens', 'novo-pedido', 'pedidos', 'entrar'].map((n) => ({ file: `mockup-app-${n}.png`, w: 520, h: 1060, html: phone(`app-${n}`, 70, 55, 380) })),
  // um notebook por tela do site
  ...['home', 'cardapio', 'novo-pedido', 'pedidos', 'entrar'].map((n) => ({ file: `mockup-web-${n}.png`, w: 1400, h: 900, html: laptop(`web-${n}`, 100, 80, 1200) })),
  // composição de abertura: site no notebook, app no celular à frente
  { file: 'mockup-apps.png', w: 1500, h: 900, html: laptop('web-home', 60, 100, 1040) + phone('app-cardapio', 1040, 70, 345, 0, 3) + phone('app-home', 890, 190, 290, 0, 2) },
  // as telas do app lado a lado
  { file: 'mockup-app-telas.png', w: 1500, h: 840, html: [['app-home', 50, 80], ['app-cardapio', 410, 40], ['app-novo-pedido', 770, 80], ['app-pedidos', 1130, 40]].map(([n, x, y]) => phone(n, x, y, 320)).join('') },
];

// apps de gestão: painel web (gweb-*) e app Android (gapp-*), mesmas molduras
const gestaoJobs = [
  ...['inicio', 'estoque', 'receitas', 'pedidos', 'mais', 'receita', 'metas', 'entrar'].map((n) => ({ file: `mockup-gapp-${n}.png`, w: 520, h: 1060, html: phone(`gapp-${n}`, 70, 55, 380) })),
  ...['inicio', 'estoque', 'receitas', 'pedidos', 'metas', 'custos', 'cardapio', 'entrar'].map((n) => ({ file: `mockup-gweb-${n}.png`, w: 1400, h: 900, html: laptop(`gweb-${n}`, 100, 80, 1200) })),
  { file: 'mockup-gestao-apps.png', w: 1500, h: 900, html: laptop('gweb-inicio', 60, 100, 1040) + phone('gapp-estoque', 1040, 70, 345, 0, 3) + phone('gapp-inicio', 890, 190, 290, 0, 2) },
  { file: 'mockup-gapp-telas.png', w: 1500, h: 840, html: [['gapp-inicio', 50, 80], ['gapp-estoque', 410, 40], ['gapp-receitas', 770, 80], ['gapp-pedidos', 1130, 40]].map(([n, x, y]) => phone(n, x, y, 320)).join('') },
];
const jobs = GESTAO ? gestaoJobs : clientJobs;

const browser = await chromium.launch({ executablePath: process.env.CHROME || path.join(os.homedir(), '.cache/ms-playwright/chromium-1243/chrome-linux64/chrome') });
for (const j of jobs) {
  const html = `<!doctype html><meta charset="utf-8"><style>${CSS}</style><div class="stage" style="width:${j.w}px;height:${j.h}px">${j.html}</div>`;
  const htmlFile = path.join(OUT, '.mockup.html');
  fs.writeFileSync(htmlFile, html);
  const ctx = await browser.newContext({ viewport: { width: j.w, height: j.h }, deviceScaleFactor: 1.5 });
  const page = await ctx.newPage();
  await page.goto('file://' + htmlFile);
  await page.waitForFunction(() => [...document.images].every((i) => i.complete && i.naturalWidth > 0));
  await page.locator('.stage').screenshot({ path: path.join(OUT, j.file), omitBackground: true });
  await ctx.close();
  console.log(j.file);
}
fs.rmSync(path.join(OUT, '.mockup.html'), { force: true });
await browser.close();
