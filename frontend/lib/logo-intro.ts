import { createTimeline, utils } from 'animejs';

/*
 * Splash do logo: o biscoito rosa aparece inteiro, treme, leva as três mordidas do símbolo (com migalhas saltando de
 * cada uma) e se estabiliza exatamente no logo estático. Sem framework: funciona em qualquer contêiner HTML.
 *
 * O logo oficial (public/brand/raykookie-simbolo-cookie.svg) é um único <path>. A geometria abaixo foi tirada dos arcos
 * desse path: um disco menos três círculos. Durante a animação, o disco é desenhado inteiro e as mordidas são círculos
 * de MÁSCARA (transparentes de verdade, servem sobre qualquer fundo); no fim, a cena é trocada pelo path oficial, então o
 * estado final é o próprio logo, sem resto de transform, máscara ou migalha.
 *
 * Tudo é animado por objetos intermediários ("proxies") e escrito como atributo SVG a cada quadro (transform, r,
 * opacity): roda igual em todos os navegadores e não deixa estilo inline para trás.
 */

/** Path oficial do símbolo (o mesmo do arquivo em public/brand). */
export const LOGO_PATH = 'M95.985 48.814A46 46 0 1 1 62.007 5.595A12 12 0 0 0 76.001 17.832A16 16 0 0 0 87.970 33.484A11 11 0 0 0 95.985 48.814Z';
export const LOGO_PINK = '#E94B8A';

/** Geometria do símbolo em viewBox 0 0 100 100: o biscoito e as três mordidas (na ordem em que são dadas). */
export const LOGO_GEOMETRY = {
  cookie: { cx: 50, cy: 50, r: 46 },
  bites: [
    { cx: 74, cy: 6, r: 12 },
    { cx: 92, cy: 18, r: 16 },
    { cx: 98, cy: 38, r: 11 },
  ],
} as const;

/** Cores das migalhas: massa dourada e tons terrosos, com um pouco do rosa da cobertura. */
const CRUMB_COLORS = ['#E8C39A', '#D9A066', '#C1844B', '#8A5A3C', '#E8C39A', LOGO_PINK];

export type LogoIntroOptions = {
  /** Espera antes de começar a tremer (ms). */
  delay?: number;
  /** Migalhas por mordida. */
  crumbsPerBite?: number;
  /** Entrada do biscoito (surge com fade e escala). Desligue ao repetir sobre o logo já na tela (ex.: no hover). */
  entrance?: boolean;
  /** 'auto' respeita prefers-reduced-motion (mostra o logo pronto); 'play' anima sempre; 'skip' nunca anima. */
  reducedMotion?: 'auto' | 'play' | 'skip';
  /** Chamado quando o logo estático está na tela (hora de ir para a tela principal). */
  onComplete?: () => void;
};

export type LogoIntroHandle = {
  /** Resolve quando a cena termina no logo estático. */
  finished: Promise<void>;
  /** Pula direto para o logo estático. */
  skip: () => void;
  /** Para tudo e limpa o contêiner (use ao desmontar). */
  destroy: () => void;
};

const NS = 'http://www.w3.org/2000/svg';
const el = <K extends keyof SVGElementTagNameMap>(tag: K, attrs: Record<string, string | number> = {}) => {
  const node = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
  return node;
};
let uid = 0;

/** O logo estático (o path oficial), num SVG que ocupa o contêiner inteiro. */
function staticLogo(label: string): SVGSVGElement {
  const svg = el('svg', { viewBox: '0 0 100 100', width: '100%', height: '100%', role: 'img', 'aria-label': label });
  svg.append(el('path', { d: LOGO_PATH, fill: LOGO_PINK }));
  return svg;
}

/** Desenha o logo estático no contêiner (sem animação). */
export function renderStaticLogo(container: HTMLElement, label = 'Raykookie') {
  container.replaceChildren(staticLogo(label));
}

/** Uma migalha: polígono irregular de 5 a 7 pontas em volta da origem (parece farelo, não bolinha perfeita). */
function crumbPath(size: number) {
  const n = utils.random(5, 7);
  const pts = Array.from({ length: n }, (_, i) => {
    const a = (i / n) * Math.PI * 2 + utils.random(-0.25, 0.25, 2);
    const r = size * utils.random(0.7, 1.15, 2);
    return `${(Math.cos(a) * r).toFixed(2)} ${(Math.sin(a) * r).toFixed(2)}`;
  });
  return `M${pts.join('L')}Z`;
}

/**
 * Toca a animação de abertura do logo dentro de `container` (o SVG ocupa 100% dele; dê largura e altura ao contêiner).
 * Devolve um handle com `finished`, `skip()` e `destroy()`.
 */
export function playLogoIntroAnimation(container: HTMLElement, options: LogoIntroOptions = {}): LogoIntroHandle {
  const { delay = 450, crumbsPerBite = 8, entrance = true, reducedMotion = 'auto', onComplete } = options;
  const label = 'Raykookie';
  let done = false;
  let resolveFinished!: () => void;
  const finished = new Promise<void>((r) => (resolveFinished = r));
  const finish = () => {
    if (done) return;
    done = true;
    renderStaticLogo(container, label); // estado final: o path oficial, sem nenhum resto da animação
    resolveFinished();
    onComplete?.();
  };

  const reduce = reducedMotion === 'skip' || (reducedMotion === 'auto' && typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches);
  if (reduce) {
    finish();
    return { finished, skip: finish, destroy: () => { done = true; container.replaceChildren(); } };
  }

  // ── 1. A cena: disco inteiro (com máscara de mordidas em r=0) e a camada das migalhas
  const { cookie, bites } = LOGO_GEOMETRY;
  const maskId = `rk-logo-bites-${++uid}`;
  const svg = el('svg', { viewBox: '0 0 100 100', width: '100%', height: '100%', overflow: 'visible', role: 'img', 'aria-label': label });
  const mask = el('mask', { id: maskId, maskUnits: 'userSpaceOnUse', x: -60, y: -60, width: 220, height: 220 });
  mask.append(el('rect', { x: -60, y: -60, width: 220, height: 220, fill: '#fff' }));
  // com entrada, as mordidas começam fechadas (biscoito inteiro); sem entrada (repetição sobre o logo), começam abertas e se fecham
  const cuts = bites.map((b) => el('circle', { cx: b.cx, cy: b.cy, r: entrance ? 0 : b.r, fill: '#000' }));
  mask.append(...cuts);
  const defs = el('defs');
  defs.append(mask);
  const body = el('g'); // é este grupo que treme
  const disc = el('circle', { cx: cookie.cx, cy: cookie.cy, r: cookie.r, fill: LOGO_PINK, mask: `url(#${maskId})` });
  body.append(disc);
  const crumbLayer = el('g');
  svg.append(defs, body, crumbLayer);
  container.replaceChildren(svg);

  // ── 2. Proxies: o anime.js anima estes números e o onUpdate escreve atributos SVG
  const intro = entrance ? { s: 0.9, o: 0 } : { s: 1, o: 1 };
  const shake = { a: 0, x: 0 };
  const cutR = bites.map((b) => ({ r: entrance ? 0 : b.r }));
  const drawBody = () => body.setAttribute('transform', `translate(${shake.x.toFixed(3)} 0) rotate(${shake.a.toFixed(3)} 50 50) translate(50 50) scale(${intro.s.toFixed(4)}) translate(-50 -50)`);
  const drawOpacity = () => body.setAttribute('opacity', intro.o.toFixed(3));

  // ── 3. Linha do tempo única
  const tl = createTimeline({ autoplay: true, onComplete: finish });

  // entrada: o biscoito inteiro surge (rápido e macio); sem entrada, ele já está na tela inteiro
  drawBody();
  drawOpacity();
  if (entrance) tl.add(intro, { s: 1, o: 1, duration: 420, ease: 'outExpo', onUpdate: () => { drawBody(); drawOpacity(); } }, 0);
  // repetição: o biscoito "se refaz" (as mordidas fecham) antes de tremer de novo, em vez de pular para inteiro
  else bites.forEach((_, i) => tl.add(cutR[i], { r: 0, duration: 260, ease: 'inOutQuad', onUpdate: () => cuts[i].setAttribute('r', cutR[i].r.toFixed(3)) }, i * 40));

  // tremor: oscilações curtas que perdem força, e assentam com um elástico
  const wiggle: { to: number; duration: number; ease: string }[] = [];
  const wiggleX: { to: number; duration: number; ease: string }[] = [];
  const swings = 16;
  for (let i = 0; i < swings; i++) {
    const k = 1 - i / swings; // amplitude cai ao longo do tremor
    const side = i % 2 ? 1 : -1;
    wiggle.push({ to: side * (1.2 + 2.6 * k), duration: 78, ease: 'inOutSine' });
    wiggleX.push({ to: side * (0.35 + 0.9 * k), duration: 78, ease: 'inOutSine' });
  }
  wiggle.push({ to: 0, duration: 700, ease: 'outElastic(1, .35)' });
  wiggleX.push({ to: 0, duration: 700, ease: 'outElastic(1, .35)' });
  const shakeStart = delay;
  tl.add(shake, { a: wiggle, x: wiggleX, onUpdate: drawBody }, shakeStart);

  // mordidas (durante o tremor), cada uma com sua chuva de migalhas no mesmo instante
  const biteTimes = [shakeStart + 260, shakeStart + 560, shakeStart + 830];
  bites.forEach((b, i) => {
    const at = biteTimes[i];
    // a mordida é "cavada": o círculo da máscara cresce do zero até o raio do logo, com um leve passo além
    tl.add(cutR[i], { r: b.r, duration: 460, ease: 'outBack(2.2)', onUpdate: () => cuts[i].setAttribute('r', cutR[i].r.toFixed(3)), onComplete: () => cuts[i].setAttribute('r', String(b.r)) }, at);

    // migalhas: nascem na área da mordida e saltam para fora do biscoito (física simples: impulso, gravidade, giro, fade)
    const out = Math.atan2(b.cy - cookie.cy, b.cx - cookie.cx); // direção "para fora" a partir do centro do biscoito
    for (let j = 0; j < crumbsPerBite; j++) {
      const size = utils.random(1.3, 2.9, 2);
      const node = el('path', { d: crumbPath(size), fill: utils.randomPick(CRUMB_COLORS) as string, opacity: 0 });
      crumbLayer.append(node);
      const x0 = b.cx - Math.cos(out) * b.r * 0.45 + utils.random(-3, 3, 2);
      const y0 = b.cy - Math.sin(out) * b.r * 0.45 + utils.random(-3, 3, 2);
      const angle = out + utils.random(-60, 60) * (Math.PI / 180);
      const dist = utils.random(12, 28, 2);
      const dx = Math.cos(angle) * dist;
      const lift = utils.random(3, 8, 2); // pulinho para cima antes de cair
      const fall = utils.random(9, 20, 2); // gravidade
      const dy = Math.sin(angle) * dist;
      const p = { x: 0, y: 0, rot: 0, s: 1, o: 0 };
      const dur = utils.random(700, 1050);
      const draw = () => {
        node.setAttribute('transform', `translate(${(x0 + p.x).toFixed(2)} ${(y0 + p.y).toFixed(2)}) rotate(${p.rot.toFixed(1)}) scale(${p.s.toFixed(3)})`);
        node.setAttribute('opacity', p.o.toFixed(3));
      };
      tl.add(p, {
        x: { to: dx, duration: dur, ease: 'outQuad' },
        y: [{ to: dy * 0.55 - lift, duration: dur * 0.35, ease: 'outQuad' }, { to: dy + fall, duration: dur * 0.65, ease: 'inQuad' }],
        rot: { to: utils.random(-320, 320), duration: dur, ease: 'outQuad' },
        s: { to: utils.random(0.45, 0.8, 2), duration: dur, ease: 'outQuad' },
        o: [{ to: 1, duration: 60, ease: 'linear' }, { to: 1, duration: dur * 0.35 }, { to: 0, duration: dur * 0.65 - 60, ease: 'outQuad' }],
        onUpdate: draw,
      }, at + utils.random(0, 70));
    }
  });

  // estabiliza: um respiro no logo completo antes de entregar para a tela principal
  tl.call(() => {}, shakeStart + swings * 78 + 700 + 250);

  return {
    finished,
    skip: () => { if (!done) { tl.pause(); finish(); } },
    destroy: () => { done = true; tl.pause(); container.replaceChildren(); },
  };
}
