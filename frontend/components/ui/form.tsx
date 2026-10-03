'use client';
import clsx from 'clsx';
import { forwardRef, useId } from 'react';
import { sanitizeNumber } from '@/lib/format';
import { toast } from './Toast';

export const inputCls =
  'w-full rounded-xl border border-line bg-surface px-3.5 py-2.5 text-[15px] text-ink placeholder:text-muted/70 transition-colors hover:border-muted/50 focus:border-pink focus:outline-none focus:ring-4 focus:ring-pink/15 disabled:opacity-60';

export function Field({ label, hint, children, className }: { label: string; hint?: string; children: (id: string) => React.ReactNode; className?: string }) {
  const id = useId();
  return (
    <div className={clsx('flex flex-col gap-1.5', className)}>
      <label htmlFor={id} className="text-sm font-medium">{label}</label>
      {children(id)}
      {hint && <p className="text-xs text-muted">{hint}</p>}
    </div>
  );
}

export const Input = forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(function Input({ className, ...p }, ref) {
  return <input ref={ref} className={clsx(inputCls, className)} {...p} />;
});
// Campo numérico (R$, quantidade, %, horas): só entram dígitos, vírgula e ponto; o resto é descartado com um aviso.
type NumberProps = Omit<React.InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'inputMode' | 'type'> & { onValueChange: (v: string) => void; integer?: boolean };
export const NumberInput = forwardRef<HTMLInputElement, NumberProps>(function NumberInput({ onValueChange, integer, className, ...p }, ref) {
  return (
    <input
      ref={ref} inputMode={integer ? 'numeric' : 'decimal'} autoComplete="off" maxLength={15} className={clsx(inputCls, className)} {...p}
      onChange={(e) => {
        const el = e.target, raw = el.value, clean = sanitizeNumber(raw, integer);
        if (clean !== raw) {
          toast.warn(integer ? 'Aqui só entram números inteiros, sem vírgula ou ponto.' : 'Aqui só entram números, vírgula e ponto. Ex.: 1.234,50');
          const pos = Math.max(0, (el.selectionStart ?? raw.length) - (raw.length - clean.length));
          requestAnimationFrame(() => el.setSelectionRange(pos, pos));
        }
        onValueChange(clean);
      }}
    />
  );
});
export function Select({ className, children, ...p }: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return <select className={clsx(inputCls, 'pr-8', className)} {...p}>{children}</select>;
}
export function Textarea({ className, ...p }: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={clsx(inputCls, 'min-h-20 resize-y', className)} {...p} />;
}

type BtnProps = React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'ghost' | 'soft' | 'danger'; size?: 'sm' | 'md'; busy?: boolean };
export function Button({ variant = 'primary', size = 'md', busy, className, children, disabled, ...p }: BtnProps) {
  return (
    <button
      disabled={disabled || busy}
      className={clsx(
        'inline-flex select-none items-center justify-center gap-2 rounded-xl font-semibold transition-all active:scale-[0.97] disabled:pointer-events-none disabled:opacity-55',
        size === 'sm' ? 'px-3 py-1.5 text-sm' : 'px-4.5 py-2.5 text-[15px]',
        variant === 'primary' && 'bg-pink text-on-pink shadow-[0_6px_18px_-8px_var(--pink)] hover:bg-pink-strong',
        variant === 'soft' && 'bg-pink-tint text-pink-strong hover:brightness-95',
        variant === 'ghost' && 'text-ink hover:bg-surface-2',
        variant === 'danger' && 'text-bad hover:bg-bad/10',
        className,
      )}
      {...p}
    >
      {busy && <span className="size-4 animate-spin rounded-full border-2 border-current border-t-transparent" aria-hidden />}
      {children}
    </button>
  );
}

export function Card({ className, children, ...p }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={clsx('rounded-3xl border border-line bg-surface shadow-card', className)} {...p}>{children}</div>;
}
