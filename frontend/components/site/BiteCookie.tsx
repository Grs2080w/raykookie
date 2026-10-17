'use client';
import { useEffect, useId, useRef, useState } from 'react';

/**
 * O biscoito do logo sendo mordido: um biscoito rosa inteiro, com gotas, leva as três mordidas do símbolo
 * (os três arcos do logo) e termina como o símbolo da marca, só o biscoito rosa. Tocar nele morde de novo.
 *
 * A forma final é exatamente `disco − 3 círculos` (a geometria do public/brand/raykookie-simbolo-cookie.svg):
 * cada mordida é um círculo de máscara que cresce a partir da borda. Tudo é CSS (transform e opacity), então roda
 * antes da hidratação; o estado de repouso do CSS já é o logo pronto, que é o que aparece sem animação.
 */

const COOKIE = { cx: 50, cy: 50, r: 46 };
// Os três círculos das mordidas do logo (centro e raio), na ordem em que são dadas.
const BITES = [
  { cx: 74, cy: 6, r: 12 },
  { cx: 92, cy: 18, r: 16 },
  { cx: 98, cy: 38, r: 11 },
];
const FIRST = 0.7; // s: o biscoito inteiro aparece um instante antes da primeira mordida
const GAP = 0.55; // s entre mordidas
const CHIPS_OUT = FIRST + GAP * 2 + 0.55; // as gotas somem depois da última mordida

// Gotas de chocolate (em rosa-framboesa): x, y, raio x, raio y, giro. Algumas caem na área das mordidas e saem junto.
const CHIPS: [number, number, number, number, number][] = [
  [30, 32, 4.4, 3.2, 20], [52, 22, 3.8, 2.8, -30], [66, 42, 4.2, 3.1, 45], [36, 52, 4.6, 3.3, -15], [56, 62, 4, 3, 30],
  [27, 70, 3.8, 2.8, 60], [72, 72, 4.4, 3.2, -20], [46, 82, 3.6, 2.6, 10], [84, 56, 3.8, 2.8, 40], [78, 28, 3.6, 2.6, -40], [60, 12, 3.4, 2.6, 25],
];

/** Farelos de cada mordida: saem para fora do biscoito (longe do centro) e caem um pouco. Determinístico (sem Math.random). */
function crumbs(bite: (typeof BITES)[number]) {
  const base = Math.atan2(bite.cy - COOKIE.cy, bite.cx - COOKIE.cx);
  return [-48, -24, 0, 24, 48].map((deg, i) => {
    const a = base + (deg * Math.PI) / 180;
    const dist = 15 + ((i * 7) % 6);
    // arredondado: Math.cos/sin podem diferir na última casa entre o Node e o navegador (aviso de hidratação)
    const n = (v: number) => Math.round(v * 100) / 100;
    return {
      x0: n(bite.cx - Math.cos(base) * bite.r * 0.35 + ((i % 2) - 0.5) * 2),
      y0: n(bite.cy - Math.sin(base) * bite.r * 0.35),
      dx: n(Math.cos(a) * dist),
      dy: n(Math.sin(a) * dist + 7), // a gravidade puxa para baixo
      r: 1.7 + ((i * 3) % 3) * 0.75,
      fill: i % 2 ? '#b81f63' : '#e94b8a', // alguns farelos são gota de chocolate (rosa-framboesa), outros massa
    };
  });
}

export function BiteCookie() {
  const [run, setRun] = useState(0); // trocar a chave remonta o SVG e a animação recomeça
  const [waiting, setWaiting] = useState(false); // nasceu fora da tela (celular): fica inteiro até a pessoa ver
  const btn = useRef<HTMLButtonElement>(null);
  const mask = `rk-bite-${useId().replace(/:/g, '')}`;

  // A animação começa sozinha no carregamento (CSS puro). Se o biscoito não está na tela nesse momento, ele espera inteiro
  // (a troca acontece fora da vista) e morde assim que a pessoa chega nele.
  useEffect(() => {
    const el = btn.current;
    if (!el || typeof IntersectionObserver === 'undefined') return;
    let first = true;
    const io = new IntersectionObserver(([e]) => {
      const visible = e.intersectionRatio >= 0.55;
      if (first) { first = false; if (!visible) setWaiting(true); else io.disconnect(); return; }
      if (visible) { setWaiting(false); setRun((n) => n + 1); io.disconnect(); }
    }, { threshold: [0, 0.55] });
    io.observe(el);
    return () => io.disconnect();
  }, []);

  return (
    <button ref={btn} type="button" className="rk-bite" data-wait={waiting || undefined} onClick={() => setRun((n) => n + 1)} aria-label="Biscoito da Raykookie. Toque para morder de novo.">
      <svg key={run} viewBox="-12 -12 124 124" aria-hidden focusable="false" style={{ '--first': `${FIRST}s`, '--gap': `${GAP}s`, '--chips-out': `${CHIPS_OUT}s` } as React.CSSProperties}>
        <defs>
          <mask id={mask} maskUnits="userSpaceOnUse" x="-12" y="-12" width="124" height="124">
            <rect x="-12" y="-12" width="124" height="124" fill="#fff" />
            {BITES.map((b, i) => <circle key={i} className="rk-bite__cut" style={{ '--i': i } as React.CSSProperties} cx={b.cx} cy={b.cy} r={b.r} fill="#000" />)}
          </mask>
        </defs>
        <g className="rk-bite__body">
          <g mask={`url(#${mask})`}>
            <circle cx={COOKIE.cx} cy={COOKIE.cy} r={COOKIE.r} fill="#e94b8a" />
            <g className="rk-bite__chips" fill="#b81f63">
              {CHIPS.map(([x, y, rx, ry, rot], i) => <ellipse key={i} cx={x} cy={y} rx={rx} ry={ry} transform={`rotate(${rot} ${x} ${y})`} />)}
            </g>
          </g>
        </g>
        <g>
          {BITES.map((b, i) => crumbs(b).map((c, j) => (
            <circle key={`${i}-${j}`} className="rk-bite__crumb" cx={c.x0} cy={c.y0} r={c.r} fill={c.fill} style={{ '--i': i, '--dx': `${c.dx}px`, '--dy': `${c.dy}px` } as React.CSSProperties} />
          )))}
        </g>
      </svg>
    </button>
  );
}
