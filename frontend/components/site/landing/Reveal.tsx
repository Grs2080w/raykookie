'use client';
import { useEffect } from 'react';

/**
 * Camada de movimento da landing: marca com `is-in` (uma vez só) o que entra na tela — `[data-reveal]`, `.shutter` e o
 * disco do hero — e dá à foto do fecho uma aproximação lenta ligada à rolagem. Sem script, nada fica escondido:
 * o CSS só esconde quando a raiz tem `rk-js`, posta pelo script em linha da página.
 */
export function Reveal() {
  useEffect(() => {
    const root = document.documentElement;
    root.classList.add('rk-js');
    const targets = document.querySelectorAll<HTMLElement>('[data-reveal], .shutter, .hero-disc');
    if (typeof IntersectionObserver === 'undefined') { targets.forEach((el) => el.classList.add('is-in')); return; }
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) if (e.isIntersecting) { e.target.classList.add('is-in'); io.unobserve(e.target); }
    }, { threshold: 0.12, rootMargin: '0px 0px -6% 0px' });
    targets.forEach((el) => io.observe(el));
    // a foto do hero já está na primeira tela: o obturador abre sozinho, logo após o disco chegar
    const first = window.setTimeout(() => document.querySelectorAll('.hero .shutter').forEach((el) => el.classList.add('is-in')), 700);

    // ── a foto do fecho se aproxima devagar enquanto a seção passa
    const photo = document.querySelector<HTMLElement>('[data-drift]');
    const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
    let frame = 0;
    const onScroll = () => {
      if (!photo || still || frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        const r = photo.parentElement!.getBoundingClientRect();
        const p = Math.min(1, Math.max(0, 1 - r.bottom / (innerHeight + r.height)));
        photo.style.transform = `scale(${1.14 - p * 0.14}) rotate(${(p - 0.5) * -10}deg)`;
      });
    };
    addEventListener('scroll', onScroll, { passive: true });
    onScroll();
    return () => { clearTimeout(first); io.disconnect(); removeEventListener('scroll', onScroll); cancelAnimationFrame(frame); root.classList.remove('rk-js'); };
  }, []);
  return null;
}
