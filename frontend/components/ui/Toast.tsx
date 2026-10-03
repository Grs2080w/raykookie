'use client';
import { AnimatePresence, motion } from 'motion/react';
import { CheckCircle2, CircleAlert, TriangleAlert } from 'lucide-react';
import { useEffect, useState } from 'react';

type T = { id: number; msg: string; kind: 'ok' | 'error' | 'warn' };
let push: (t: Omit<T, 'id'>) => void = () => {};
export const toast = {
  ok: (msg: string) => push({ msg, kind: 'ok' }),
  error: (msg: string) => push({ msg, kind: 'error' }),
  // aviso leve (ex.: caractere não permitido); não repete a mesma mensagem a cada tecla
  warn: (msg: string) => {
    const now = Date.now();
    if (msg === lastWarn.msg && now - lastWarn.at < 2500) return;
    lastWarn = { msg, at: now };
    push({ msg, kind: 'warn' });
  },
};
let lastWarn = { msg: '', at: 0 };

export function ToastHost() {
  const [items, setItems] = useState<T[]>([]);
  useEffect(() => {
    let n = 0;
    push = (t) => {
      const id = ++n;
      setItems((s) => [...s, { ...t, id }]);
      setTimeout(() => setItems((s) => s.filter((x) => x.id !== id)), t.kind === 'error' ? 6000 : t.kind === 'warn' ? 3800 : 3200);
    };
    return () => { push = () => {}; };
  }, []);
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-20 z-[100] flex flex-col items-center gap-2 px-4 md:bottom-6" role="status" aria-live="polite">
      <AnimatePresence>
        {items.map((t) => (
          <motion.div
            key={t.id} layout
            initial={{ opacity: 0, y: 24, scale: 0.96 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: 8, scale: 0.98 }}
            transition={{ type: 'spring', stiffness: 420, damping: 30 }}
            className="pointer-events-auto flex max-w-md items-center gap-2.5 rounded-2xl border border-line bg-surface px-4 py-3 text-sm font-medium shadow-card"
          >
            {t.kind === 'ok' ? <CheckCircle2 size={18} className="shrink-0 text-ok" /> : t.kind === 'warn' ? <TriangleAlert size={18} className="shrink-0 text-warn" /> : <CircleAlert size={18} className="shrink-0 text-bad" />}
            {t.msg}
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}
