'use client';
import { AnimatePresence, motion } from 'motion/react';
import { LogOut, Menu, X } from 'lucide-react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import clsx from 'clsx';
import { Logo } from './Logo';
import { NavTree, useNavSections } from './NavTree';
import { ThemeToggle } from './ThemeToggle';
import { NAV_BAR, isActive } from '@/lib/nav';

export function Shell({ username, children }: { username: string; children: React.ReactNode }) {
  const path = usePathname();
  const router = useRouter();
  const [menu, setMenu] = useState(false);
  const sections = useNavSections(path);
  const active = (href: string) => isActive(path, href);

  // a gaveta fecha ao navegar e com Esc
  useEffect(() => setMenu(false), [path]);
  useEffect(() => {
    if (!menu) return;
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && setMenu(false);
    window.addEventListener('keydown', esc);
    return () => window.removeEventListener('keydown', esc);
  }, [menu]);

  async function logout() {
    await fetch('/api/auth/logout', { method: 'POST' });
    router.replace('/login');
    router.refresh();
  }

  return (
    <div className="min-h-dvh md:pl-64">
      {/* Sidebar (desktop) */}
      <aside className="fixed inset-y-0 left-0 hidden w-64 flex-col border-r border-line bg-surface/70 px-4 py-6 backdrop-blur md:flex">
        <Link href="/dashboard" aria-label="raykookie business, início" className="px-2"><Logo business imgClassName="h-11" /></Link>
        <div className="mt-7 min-h-0 flex-1 overflow-y-auto pb-4 pr-1"><NavTree path={path} state={sections} pill="nav-pill" /></div>
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
        <Link href="/dashboard" aria-label="raykookie business, início" className="md:hidden"><Logo business imgClassName="h-9" /></Link>
        <div className="flex items-center gap-2">
          <ThemeToggle />
          <button onClick={() => setMenu(true)} aria-label="Abrir menu" aria-expanded={menu} className="grid size-11 place-items-center rounded-full bg-pink-tint text-pink-strong md:hidden"><Menu size={21} /></button>
        </div>
      </header>

      <main className="mx-auto w-full max-w-[1200px] px-4 pb-28 pt-6 md:px-8 md:pb-16 md:pt-8">{children}</main>

      {/* Barra inferior (mobile) */}
      <nav className="fixed inset-x-0 bottom-0 z-30 grid grid-cols-5 border-t border-line bg-surface/95 px-1 pb-[max(env(safe-area-inset-bottom),6px)] pt-1.5 backdrop-blur md:hidden" aria-label="Principal">
        {NAV_BAR.map(({ href, label, icon: Icon }) => (
          <Link key={href} href={href} aria-current={active(href) ? 'page' : undefined}
            className={clsx('flex flex-col items-center gap-0.5 rounded-xl py-1.5 text-[11px] font-medium', active(href) ? 'text-pink-strong' : 'text-muted')}>
            <span className={clsx('grid h-7 w-12 place-items-center rounded-full transition-colors', active(href) && 'bg-pink-tint')}><Icon size={19} /></span>
            {label}
          </Link>
        ))}
      </nav>
      {/* Menu do celular: a mesma árvore da sidebar */}
      <AnimatePresence>
        {menu && (
          <div className="fixed inset-0 z-50 md:hidden">
            <motion.div className="absolute inset-0 bg-black/50" onClick={() => setMenu(false)} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} />
            <motion.aside role="dialog" aria-modal="true" aria-label="Menu" className="absolute inset-y-0 left-0 flex w-[min(20rem,88vw)] flex-col border-r border-line bg-surface px-4 pb-[max(env(safe-area-inset-bottom),16px)] pt-5"
              initial={{ x: '-100%' }} animate={{ x: 0 }} exit={{ x: '-100%' }} transition={{ type: 'spring', stiffness: 420, damping: 40 }}>
              <div className="flex items-center justify-between px-2">
                <Logo business imgClassName="h-11" />
                <button onClick={() => setMenu(false)} aria-label="Fechar menu" className="grid size-11 place-items-center rounded-full text-muted hover:bg-surface-2"><X size={20} /></button>
              </div>
              <div className="mt-5 min-h-0 flex-1 overflow-y-auto pb-4"><NavTree path={path} state={sections} touch pill="nav-pill-m" onNavigate={() => setMenu(false)} /></div>
              <div className="flex items-center justify-between gap-2 rounded-2xl border border-line bg-bg p-2.5">
                <div className="flex min-w-0 items-center gap-2.5">
                  <span className="grid size-10 shrink-0 place-items-center rounded-full bg-pink-tint font-display font-semibold text-pink-strong">{username[0]?.toUpperCase()}</span>
                  <span className="truncate text-sm font-medium">{username}</span>
                </div>
                <button onClick={logout} aria-label="Sair" title="Sair" className="grid size-11 place-items-center rounded-lg text-muted hover:bg-surface-2 hover:text-bad"><LogOut size={18} /></button>
              </div>
            </motion.aside>
          </div>
        )}
      </AnimatePresence>
    </div>
  );
}
