'use client';
import { Moon, Sun } from 'lucide-react';
import { useEffect, useState } from 'react';

export function ThemeToggle() {
  const [theme, setTheme] = useState<'light' | 'dark'>('light');
  useEffect(() => setTheme((document.documentElement.dataset.theme as 'light' | 'dark') || 'light'), []);

  function toggle(e: React.MouseEvent<HTMLButtonElement>) {
    const next = theme === 'dark' ? 'light' : 'dark';
    const apply = () => { document.documentElement.dataset.theme = next; try { localStorage.setItem('theme', next); } catch {} setTheme(next); };
    const r = e.currentTarget.getBoundingClientRect();
    document.documentElement.style.setProperty('--vt-x', `${r.left + r.width / 2}px`);
    document.documentElement.style.setProperty('--vt-y', `${r.top + r.height / 2}px`);
    const start = (document as any).startViewTransition?.bind(document);
    if (start && !matchMedia('(prefers-reduced-motion: reduce)').matches) start(apply); else apply();
  }
  return (
    <button onClick={toggle} aria-label={theme === 'dark' ? 'Usar tema claro' : 'Usar tema escuro'} title={theme === 'dark' ? 'Tema claro' : 'Tema escuro'}
      className="grid size-10 place-items-center rounded-xl border border-line bg-surface text-ink transition-colors hover:bg-surface-2 active:scale-95">
      {theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
    </button>
  );
}
