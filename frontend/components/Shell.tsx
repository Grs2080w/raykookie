'use client';
import { motion } from 'motion/react';
import { ChefHat, ClipboardList, LayoutDashboard, LogOut, MapPin, Package, Settings, ShieldCheck, Store, Tags, Target, Undo2 } from 'lucide-react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useState } from 'react';
import clsx from 'clsx';
import { Logo } from './Logo';
import { ThemeToggle } from './ThemeToggle';

const NAV = [
  { href: '/dashboard', label: 'Início', icon: LayoutDashboard },
  { href: '/estoque', label: 'Estoque', icon: Package },
  { href: '/receitas', label: 'Receitas', icon: ChefHat },
  { href: '/pedidos', label: 'Pedidos', icon: ClipboardList },
  { href: '/metas', label: 'Metas', icon: Target },
  { href: '/devolucoes', label: 'Devoluções', icon: Undo2 },
  { href: '/fornecedores', label: 'Fornecedores', icon: Store },
  { href: '/categorias', label: 'Categorias', icon: Tags },
  { href: '/enderecos', label: 'Endereço de retirada', icon: MapPin },
  { href: '/solicitacoes-privacidade', label: 'Privacidade dos clientes', icon: ShieldCheck },
  { href: '/configuracoes', label: 'Custos e ajustes', icon: Settings },
];
const MOBILE = NAV.slice(0, 5);

export function Shell({ username, children }: { username: string; children: React.ReactNode }) {
  const path = usePathname();
  const router = useRouter();
  const [menu, setMenu] = useState(false);
  const active = (href: string) => path.startsWith(href);

  async function logout() {
    await fetch('/api/auth/logout', { method: 'POST' });
    router.replace('/login');
    router.refresh();
  }

  return (
    <div className="min-h-dvh md:pl-64">
      {/* Sidebar (desktop) */}
      <aside className="fixed inset-y-0 left-0 hidden w-64 flex-col border-r border-line bg-surface/70 px-4 py-6 backdrop-blur md:flex">
        <Link href="/dashboard" aria-label="Raykookie, início" className="px-2"><Logo imgClassName="h-8" /></Link>
        <nav className="mt-9 flex flex-1 flex-col gap-1" aria-label="Principal">
          {NAV.map(({ href, label, icon: Icon }) => (
            <Link key={href} href={href} aria-current={active(href) ? 'page' : undefined}
              className={clsx('relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-[15px] font-medium transition-colors', active(href) ? 'text-pink-strong' : 'text-muted hover:bg-surface-2 hover:text-ink')}>
              {active(href) && <motion.span layoutId="nav-pill" className="absolute inset-0 rounded-xl bg-pink-tint" transition={{ type: 'spring', stiffness: 500, damping: 38 }} />}
              <Icon size={19} className="relative" />
              <span className="relative">{label}</span>
            </Link>
          ))}
        </nav>
        <div className="flex items-center justify-between gap-2 rounded-2xl border border-line bg-surface p-2.5">
          <div className="flex min-w-0 items-center gap-2.5">
            <span className="grid size-9 shrink-0 place-items-center rounded-full bg-pink-tint font-display font-semibold text-pink-strong">{username[0]?.toUpperCase()}</span>
            <span className="truncate text-sm font-medium">{username}</span>
          </div>
          <button onClick={logout} aria-label="Sair" title="Sair" className="grid size-9 place-items-center rounded-lg text-muted transition-colors hover:bg-surface-2 hover:text-bad"><LogOut size={17} /></button>
        </div>
      </aside>

      {/* Barra superior */}
      <header className="sticky top-0 z-30 flex items-center justify-between gap-3 border-b border-line bg-bg/85 px-4 py-3 backdrop-blur md:justify-end md:px-8">
        <Link href="/dashboard" aria-label="Raykookie, início" className="md:hidden"><Logo variant="compacta" imgClassName="h-10" /></Link>
        <div className="flex items-center gap-2">
          <ThemeToggle />
          <div className="relative md:hidden">
            <button onClick={() => setMenu((v) => !v)} aria-label="Menu da conta" aria-expanded={menu}
              className="grid size-10 place-items-center rounded-full bg-pink-tint font-display font-semibold text-pink-strong">{username[0]?.toUpperCase()}</button>
            {menu && (
              <div className="absolute right-0 top-12 z-40 w-56 rounded-2xl border border-line bg-surface p-1.5 shadow-card" onClick={() => setMenu(false)}>
                <Link href="/categorias" className="flex items-center gap-2.5 rounded-xl px-3 py-2.5 text-sm hover:bg-surface-2"><Tags size={17} />Categorias</Link>
                <Link href="/configuracoes" className="flex items-center gap-2.5 rounded-xl px-3 py-2.5 text-sm hover:bg-surface-2"><Settings size={17} />Custos e ajustes</Link>
                <button onClick={logout} className="flex w-full items-center gap-2.5 rounded-xl px-3 py-2.5 text-sm text-bad hover:bg-bad/10"><LogOut size={17} />Sair ({username})</button>
              </div>
            )}
          </div>
        </div>
      </header>

      <main className="mx-auto w-full max-w-[1200px] px-4 pb-28 pt-6 md:px-8 md:pb-16 md:pt-8">{children}</main>

      {/* Barra inferior (mobile) */}
      <nav className="fixed inset-x-0 bottom-0 z-30 grid grid-cols-5 border-t border-line bg-surface/95 px-1 pb-[max(env(safe-area-inset-bottom),6px)] pt-1.5 backdrop-blur md:hidden" aria-label="Principal">
        {MOBILE.map(({ href, label, icon: Icon }) => (
          <Link key={href} href={href} aria-current={active(href) ? 'page' : undefined}
            className={clsx('flex flex-col items-center gap-0.5 rounded-xl py-1.5 text-[11px] font-medium', active(href) ? 'text-pink-strong' : 'text-muted')}>
            <span className={clsx('grid h-7 w-12 place-items-center rounded-full transition-colors', active(href) && 'bg-pink-tint')}><Icon size={19} /></span>
            {label}
          </Link>
        ))}
      </nav>
    </div>
  );
}
