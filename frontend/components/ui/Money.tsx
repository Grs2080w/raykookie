'use client';
import { animate, useMotionValue, useReducedMotion } from 'motion/react';
import { useEffect, useRef, useState } from 'react';
import { money, moneyPrecise, percent, number } from '@/lib/format';

// Número que "rola" até o novo valor (respeita movimento reduzido).
export function Counter({ value, kind = 'money', className }: { value: number; kind?: 'money' | 'precise' | 'percent' | 'int'; className?: string }) {
  const reduce = useReducedMotion();
  const mv = useMotionValue(value);
  const [text, setText] = useState('');
  const fmt = kind === 'money' ? money : kind === 'precise' ? moneyPrecise : kind === 'percent' ? percent : number;
  const first = useRef(true);
  useEffect(() => {
    if (reduce) { mv.set(value); setText(fmt(value)); return; }
    const c = animate(mv, value, { duration: first.current ? 0.9 : 0.45, ease: [0.22, 1, 0.36, 1], onUpdate: (v) => setText(fmt(v)) });
    first.current = false;
    return () => c.stop();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, reduce]);
  return <span className={`tnum ${className ?? ''}`}>{text || fmt(value)}</span>;
}
