'use client';
import { useEffect, useRef } from 'react';
import type { LogoIntroHandle, LogoIntroOptions } from '@/lib/logo-intro';

// o anime.js (e a animação) só baixam quando o logo vai tocar: ficam fora do JS inicial da página
type Lib = typeof import('@/lib/logo-intro');
let lib: Promise<Lib> | null = null;
const loadLib = () => (lib ??= import('@/lib/logo-intro'));

type Props = {
  /** Tamanho em px; sem ele, quem manda é a classe (largura e altura no CSS). */
  size?: number;
  className?: string;
  /** Repete a animação a cada vez que o ponteiro entra no logo (no celular, a cada toque). */
  replayOnHover?: boolean;
  /** Só começa quando pelo menos metade do logo está na tela (ex.: abaixo da dobra no celular). */
  playWhenVisible?: boolean;
} & LogoIntroOptions;

/**
 * Logo animado: o biscoito rosa treme, leva as três mordidas (com migalhas) e para no símbolo estático.
 * `onComplete` avisa quando o logo pronto está na tela. Com movimento reduzido, mostra direto o logo (e não repete).
 */
export function LogoIntro({ size, className, replayOnHover = false, playWhenVisible = false, ...options }: Props) {
  const box = useRef<HTMLDivElement>(null);
  const handle = useRef<LogoIntroHandle | null>(null);
  const playing = useRef(false);
  const opts = useRef(options);
  opts.current = options;

  const alive = useRef(true);
  const play = async (repeat: boolean) => {
    const el = box.current;
    if (!el || playing.current) return; // no meio de uma mordida, um novo hover não recomeça (evita tranco)
    playing.current = true;
    const { playLogoIntroAnimation } = await loadLib();
    if (!alive.current) return;
    handle.current?.destroy();
    handle.current = playLogoIntroAnimation(el, {
      ...opts.current,
      // repetição sobre o logo já na tela: sem entrada e com espera curta (resposta ao gesto)
      ...(repeat ? { entrance: false, delay: 300 } : {}),
      onComplete: () => { playing.current = false; opts.current.onComplete?.(); },
    });
  };

  useEffect(() => {
    const el = box.current;
    if (!el) return;
    alive.current = true;
    const stop = () => { alive.current = false; handle.current?.destroy(); };
    if (!playWhenVisible || typeof IntersectionObserver === 'undefined') { void play(false); return stop; }
    void loadLib(); // já vai baixando enquanto o logo não aparece
    const io = new IntersectionObserver(([e]) => { if (e.intersectionRatio >= 0.5) { io.disconnect(); void play(false); } }, { threshold: [0, 0.5] });
    io.observe(el);
    return () => { io.disconnect(); stop(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- uma vez por montagem
  }, []);

  const replay = replayOnHover ? () => { void play(true); } : undefined;
  return (
    <div
      ref={box}
      className={className}
      style={size ? { width: size, height: size } : undefined}
      onPointerEnter={replay && ((e) => { if (e.pointerType === 'mouse') replay(); })}
      onClick={replay}
    >
      <noscript>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/brand/raykookie-simbolo-cookie.svg" alt="Raykookie" width={size ?? 320} height={size ?? 320} style={{ width: '100%', height: 'auto' }} />
      </noscript>
    </div>
  );
}
