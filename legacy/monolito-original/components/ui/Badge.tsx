import clsx from 'clsx';
export type Tone = 'ok' | 'warn' | 'bad' | 'pink' | 'neutral';
const tones: Record<Tone, string> = {
  ok: 'bg-ok/14 text-ok', warn: 'bg-warn/16 text-warn', bad: 'bg-bad/12 text-bad',
  pink: 'bg-pink-tint text-pink-strong', neutral: 'bg-surface-2 text-muted',
};
export function Badge({ tone = 'neutral', children, dot = true }: { tone?: Tone; children: React.ReactNode; dot?: boolean }) {
  return (
    <span className={clsx('inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-semibold', tones[tone])}>
      {dot && <span className="size-1.5 rounded-full bg-current" />}{children}
    </span>
  );
}
