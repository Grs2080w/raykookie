// Imagens prontas para stories (1080×1920): o cardápio e um produto. Só usam o que já é público no cardápio da loja.
import { ImageResponse } from 'next/og';
import { weightLabel } from '@shared/shop';
import type { MenuItem } from '@shared/types';
import { siteUrl } from './site';

const W = 1080, H = 1920;
const PINK = '#d42a76', INK = '#2a1a22', CREAM = '#fff6f0';
const money = (v: number) => `R$ ${v.toFixed(2).replace('.', ',')}`;
const host = () => siteUrl('/cardapio').replace(/^https?:\/\//, '');

const base = { width: W, height: H, display: 'flex', flexDirection: 'column' as const, background: `linear-gradient(160deg, ${CREAM} 0%, #ffd9e8 100%)`, padding: 80, fontFamily: 'sans-serif', color: INK };

export function cardapioStory(items: MenuItem[]): ImageResponse {
  const shown = items.slice(0, 9);
  const more = items.length - shown.length;
  return new ImageResponse(
    (
      <div style={base}>
        <div style={{ display: 'flex', fontSize: 40, fontWeight: 700, letterSpacing: 6, color: PINK }}>RAYKOOKIE</div>
        <div style={{ display: 'flex', fontSize: 120, fontWeight: 800, lineHeight: 1.05, marginTop: 24 }}>Cardápio</div>
        <div style={{ display: 'flex', fontSize: 38, marginTop: 12, opacity: 0.75 }}>Feito à mão, sob encomenda</div>
        <div style={{ display: 'flex', flexDirection: 'column', marginTop: 56, flex: 1 }}>
          {shown.map((m) => (
            <div key={m.id} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', background: 'white', borderRadius: 36, padding: '30px 40px', marginBottom: 22, boxShadow: '0 6px 24px rgba(212,42,118,.12)' }}>
              <div style={{ display: 'flex', flexDirection: 'column', maxWidth: 640 }}>
                <div style={{ display: 'flex', fontSize: 44, fontWeight: 700 }}>{m.name}</div>
                {weightLabel(m.weight_g) ? <div style={{ display: 'flex', fontSize: 30, opacity: 0.6 }}>{weightLabel(m.weight_g)} cada</div> : null}
              </div>
              <div style={{ display: 'flex', fontSize: 52, fontWeight: 800, color: PINK }}>{money(m.price)}</div>
            </div>
          ))}
          {more > 0 ? <div style={{ display: 'flex', fontSize: 34, opacity: 0.7 }}>e mais {more} no link</div> : null}
        </div>
        <div style={{ display: 'flex', justifyContent: 'center', fontSize: 40, fontWeight: 700, background: PINK, color: 'white', borderRadius: 40, padding: '30px 0' }}>Peça em {host()}</div>
      </div>
    ),
    { width: W, height: H },
  );
}

export function produtoStory(item: MenuItem, photo: { type: string; data: Buffer } | null): ImageResponse {
  const src = photo && /^image\/(jpeg|png)$/.test(photo.type) ? `data:${photo.type};base64,${photo.data.toString('base64')}` : null;
  return new ImageResponse(
    (
      <div style={base}>
        <div style={{ display: 'flex', fontSize: 40, fontWeight: 700, letterSpacing: 6, color: PINK }}>RAYKOOKIE</div>
        <div style={{ display: 'flex', flex: 1, alignItems: 'center', justifyContent: 'center', marginTop: 40 }}>
          {src
            // eslint-disable-next-line @next/next/no-img-element
            ? <img src={src} alt="" width={920} height={920} style={{ width: 920, height: 920, objectFit: 'cover', borderRadius: 60 }} />
            : <div style={{ display: 'flex', width: 920, height: 920, borderRadius: 60, background: 'white', alignItems: 'center', justifyContent: 'center', fontSize: 200 }}>🍪</div>}
        </div>
        <div style={{ display: 'flex', fontSize: 84, fontWeight: 800, lineHeight: 1.1, marginTop: 40 }}>{item.name}</div>
        <div style={{ display: 'flex', alignItems: 'baseline', marginTop: 16 }}>
          <div style={{ display: 'flex', fontSize: 96, fontWeight: 800, color: PINK }}>{money(item.price)}</div>
          {weightLabel(item.weight_g) ? <div style={{ display: 'flex', fontSize: 40, opacity: 0.65, marginLeft: 28 }}>{weightLabel(item.weight_g)} cada</div> : null}
        </div>
        <div style={{ display: 'flex', justifyContent: 'center', fontSize: 40, fontWeight: 700, background: PINK, color: 'white', borderRadius: 40, padding: '30px 0', marginTop: 48 }}>Peça em {host()}</div>
      </div>
    ),
    { width: W, height: H },
  );
}
