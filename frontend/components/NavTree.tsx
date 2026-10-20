'use client';
import { AnimatePresence, motion } from 'motion/react';
import { ChevronDown } from 'lucide-react';
import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import clsx from 'clsx';
import { NAV_HOME, NAV_SECTIONS, isActive, sectionOf, type NavItem } from '@/lib/nav';

const KEY = 'rk.nav.recolhidas';

/** Seções recolhidas pelo usuário (lembradas entre sessões). A seção da aba ativa abre sozinha ao navegar. */
export function useNavSections(path: string) {
  const [closed, setClosed] = useState<string[]>([]);
  useEffect(() => {
    try { setClosed(JSON.parse(localStorage.getItem(KEY) || '[]')); } catch { /* sem armazenamento: tudo aberto */ }
  }, []);
  useEffect(() => {
    const id = sectionOf(path);
    if (id) setClosed((c) => (c.includes(id) ? c.filter((x) => x !== id) : c));
  }, [path]);
  const toggle = useCallback((id: string) => {
    setClosed((c) => {
      const next = c.includes(id) ? c.filter((x) => x !== id) : [...c, id];
      try { localStorage.setItem(KEY, JSON.stringify(next)); } catch { /* ignora */ }
      return next;
    });
  }, []);
  return { isOpen: (id: string) => !closed.includes(id), toggle };
}

/** Árvore de abas (sidebar e gaveta do celular): Início solto no topo e as seções recolhíveis. `touch` aumenta as áreas de toque. */
export function NavTree({ path, state, touch = false, pill, onNavigate }: {
  path: string;
  state: ReturnType<typeof useNavSections>;
  touch?: boolean;
  /** id do indicador animado: distinto em cada instância, para as duas não se misturarem */
  pill: string;
  onNavigate?: () => void;
}) {
  const row = (item: NavItem, nested: boolean) => {
    const on = isActive(path, item.href);
    const Icon = item.icon;
    return (
      <Link key={item.href} href={item.href} onClick={onNavigate} aria-current={on ? 'page' : undefined}
        className={clsx('relative flex items-center gap-3 rounded-xl px-3 text-[15px] font-medium transition-colors', touch ? 'min-h-12' : 'py-2.5', nested && 'ml-2', on ? 'text-pink-strong' : 'text-muted hover:bg-surface-2 hover:text-ink')}>
        {on && <motion.span layoutId={pill} className="absolute inset-0 rounded-xl bg-pink-tint" transition={{ type: 'spring', stiffness: 500, damping: 38 }} />}
        <Icon size={19} className="relative" />
        <span className="relative">{item.label}</span>
      </Link>
    );
  };

  return (
    <nav className="flex flex-col gap-1" aria-label="Principal">
      {row(NAV_HOME, false)}
      {NAV_SECTIONS.map((s) => {
        const open = state.isOpen(s.id);
        return (
          <div key={s.id} className="mt-3">
            <button type="button" onClick={() => state.toggle(s.id)} aria-expanded={open} aria-controls={`nav-${s.id}`}
              className={clsx('flex w-full items-center justify-between rounded-lg px-3 text-[11px] font-semibold uppercase tracking-[0.08em] text-muted/80 transition-colors hover:text-ink', touch ? 'min-h-11' : 'py-1.5')}>
              {s.title}
              <ChevronDown size={15} className={clsx('transition-transform duration-200', !open && '-rotate-90')} />
            </button>
            <AnimatePresence initial={false}>
              {open && (
                <motion.div id={`nav-${s.id}`} key="itens" initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }}
                  transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }} className="flex flex-col gap-0.5 overflow-hidden">
                  {s.items.map((i) => row(i, true))}
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        );
      })}
    </nav>
  );
}
