'use client';
import { motion } from 'motion/react';
import { Counter } from './ui/Money';

export function GoalRing({ pct, size = 160 }: { pct: number; size?: number }) {
  const R = 70, C = 2 * Math.PI * R;
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}>
      <svg viewBox="0 0 180 180" className="size-full -rotate-90" role="img" aria-label={`${Math.round(pct)}% da meta de faturamento`}>
        <circle cx="90" cy="90" r={R} fill="none" stroke="var(--surface-2)" strokeWidth="15" />
        <motion.circle cx="90" cy="90" r={R} fill="none" stroke="var(--pink)" strokeWidth="15" strokeLinecap="round" strokeDasharray={C}
          initial={{ strokeDashoffset: C }} animate={{ strokeDashoffset: C * (1 - Math.min(pct, 100) / 100) }} transition={{ duration: 1.2, ease: [0.22, 1, 0.36, 1] }} />
      </svg>
      <div className="absolute inset-0 flex items-center justify-center font-display text-4xl font-semibold"><Counter value={pct} kind="int" />%</div>
    </div>
  );
}
