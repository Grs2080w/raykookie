'use client';
import { useEffect, useRef, useState } from 'react';

/**
 * Chips das categorias do cardápio: ficam presos sob o cabeçalho e marcam a categoria que está na tela.
 * Cada chip é um link de âncora comum (`#cat-…`), então funciona sem JavaScript; o JS só acompanha a rolagem.
 */
export function CategoryNav({ groups }: { groups: { id: string; name: string }[] }) {
  const [active, setActive] = useState(groups[0]?.id);
  const nav = useRef<HTMLElement>(null);

  // acompanha a rolagem: a categoria ativa é a primeira (na ordem do cardápio) que cruza a faixa logo abaixo dos chips
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return;
    const seen = new Set<string>();
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) (e.isIntersecting ? seen.add(e.target.id) : seen.delete(e.target.id));
      const first = groups.find((g) => seen.has(`cat-${g.id}`));
      if (first) setActive(first.id);
      // de volta ao topo (antes da primeira categoria): marca a primeira
      else if (groups[0] && (document.getElementById(`cat-${groups[0].id}`)?.getBoundingClientRect().top ?? 0) > innerHeight * 0.45) setActive(groups[0].id);
    }, { rootMargin: '-136px 0px -55% 0px' });
    for (const g of groups) { const el = document.getElementById(`cat-${g.id}`); if (el) io.observe(el); }
    return () => io.disconnect();
  }, [groups]);

  // o chip ativo acompanha dentro da faixa de chips (que rola na horizontal no celular)
  useEffect(() => {
    const box = nav.current;
    const chip = box?.querySelector<HTMLElement>('[aria-current="true"]');
    if (!box || !chip || box.scrollWidth <= box.clientWidth) return;
    const smooth = !matchMedia('(prefers-reduced-motion: reduce)').matches;
    box.scrollTo({ left: chip.offsetLeft - (box.clientWidth - chip.offsetWidth) / 2, behavior: smooth ? 'smooth' : 'auto' });
  }, [active]);

  return (
    <nav ref={nav} className="rk-chips" aria-label="Categorias do cardápio">
      {groups.map((g) => (
        <a key={g.id} href={`#cat-${g.id}`} aria-current={active === g.id ? 'true' : undefined} onClick={() => setActive(g.id)}>{g.name}</a>
      ))}
    </nav>
  );
}
