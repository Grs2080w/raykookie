'use client';
import { ShoppingBag } from 'lucide-react';
import Link from 'next/link';
import { useEffect, useState } from 'react';

/** Barra fixa "Fazer pedido" do celular: fica escondida enquanto o botão principal (`targetId`) está na tela e desliza para cima quando ele sai. */
export function MobileOrderBar({ href, targetId, className }: { href: string; targetId: string; className: string }) {
  const [show, setShow] = useState(false);
  useEffect(() => {
    const el = document.getElementById(targetId);
    if (!el) { setShow(true); return; } // sem botão principal: a barra é o botão
    const io = new IntersectionObserver(([e]) => setShow(!e.isIntersecting), { rootMargin: '-56px 0px 0px 0px' }); // 56px: altura do cabeçalho fixo
    io.observe(el);
    return () => io.disconnect();
  }, [targetId]);
  return (
    <div
      aria-hidden={!show}
      inert={!show}
      className={`fixed inset-x-0 bottom-0 z-40 border-t border-line bg-white px-4 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-3 shadow-[0_-6px_16px_-10px_rgba(0,0,0,0.25)] transition-all duration-300 ease-out motion-reduce:transition-none md:hidden ${show ? 'translate-y-0 opacity-100' : 'pointer-events-none translate-y-full opacity-0'}`}
    >
      <Link href={href} className={`${className} w-full`}><ShoppingBag size={19} />Fazer pedido</Link>
    </div>
  );
}
