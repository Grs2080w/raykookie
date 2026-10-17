'use client';
import { useEffect, useRef } from 'react';
import { playLogoIntroAnimation, type LogoIntroOptions } from '@/lib/logo-intro';

/**
 * Splash do logo: o biscoito rosa treme, leva as três mordidas (com migalhas) e para no símbolo estático.
 * `onComplete` avisa quando o logo pronto está na tela (hora de ir para a tela principal). Com movimento reduzido,
 * mostra direto o logo. Dê o tamanho pelo `size` (px) ou pela classe.
 */
export function LogoIntro({ size = 160, className, ...options }: { size?: number; className?: string } & LogoIntroOptions) {
  const box = useRef<HTMLDivElement>(null);
  const done = useRef(options.onComplete);
  done.current = options.onComplete;

  useEffect(() => {
    if (!box.current) return;
    // a função desenha o próprio SVG no contêiner; ao desmontar, para a linha do tempo e limpa
    const handle = playLogoIntroAnimation(box.current, { ...options, onComplete: () => done.current?.() });
    return () => handle.destroy();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- toca uma vez por montagem
  }, []);

  return (
    <div ref={box} className={className} style={{ width: size, height: size }}>
      <noscript>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/brand/raykookie-simbolo-cookie.svg" alt="Raykookie" width={size} height={size} />
      </noscript>
    </div>
  );
}
