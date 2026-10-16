// O cookie mordido do logo (o segundo "o" de raykookie) como polígono de clip-path.
// Todas as versões têm o mesmo número de pontos, então o CSS anima de uma para a outra (o disco "leva a mordida").

const POINTS = 180;
const R = 0.5;
// três mordidas no quadrante de cima à direita, como no logo (ângulos em graus, 0 = direita, negativo = para cima)
const BITES = [-62, -37, -12].map((deg) => ({ a: (deg * Math.PI) / 180, d: 0.545, r: 0.118 }));

/** Distância do centro até a borda do cookie no ângulo `t`, com as `n` primeiras mordidas. */
function radius(t: number, n: number): number {
  let r = R;
  for (const b of BITES.slice(0, n)) {
    const delta = t - b.a;
    const s = b.d * Math.sin(delta);
    if (b.r * b.r < s * s) continue;
    const near = b.d * Math.cos(delta) - Math.sqrt(b.r * b.r - s * s);
    if (near > 0 && near < r) r = near;
  }
  return r;
}

const cache = new Map<number, string>();

/** `polygon(...)` em porcentagem da caixa do elemento: 0 = cookie inteiro, 3 = mordido como no logo. */
export function bitePolygon(bites: 0 | 1 | 2 | 3 = 3): string {
  const hit = cache.get(bites);
  if (hit) return hit;
  const pts: string[] = [];
  for (let i = 0; i < POINTS; i++) {
    const t = -Math.PI + (i / POINTS) * Math.PI * 2;
    const r = radius(t, bites);
    pts.push(`${(50 + r * Math.cos(t) * 100).toFixed(2)}% ${(50 + r * Math.sin(t) * 100).toFixed(2)}%`);
  }
  const out = `polygon(${pts.join(',')})`;
  cache.set(bites, out);
  return out;
}

/** Variáveis CSS com as quatro etapas da mordida, para pôr no `style` da raiz da página. */
export const biteVars = () => ({
  '--bite-0': bitePolygon(0),
  '--bite-1': bitePolygon(1),
  '--bite-2': bitePolygon(2),
  '--bite-3': bitePolygon(3),
}) as React.CSSProperties;
