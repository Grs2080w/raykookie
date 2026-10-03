import { Tag } from 'lucide-react';
import { ICONS } from './icon-registry';

export function CategoryIcon({ name, size = 18, className }: { name?: string | null; size?: number; className?: string }) {
  const Icon = (name && ICONS[name]) || Tag;
  return <Icon size={size} strokeWidth={1.9} className={className} aria-hidden />;
}

export function CategoryChip({ name, icon, color, size = 'md' }: { name: string; icon: string; color: string; size?: 'sm' | 'md' }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full font-medium ${size === 'sm' ? 'px-2 py-0.5 text-xs' : 'px-2.5 py-1 text-[13px]'}`}
      style={{ background: `color-mix(in oklab, ${color} 16%, transparent)`, color: `color-mix(in oklab, ${color} 78%, var(--ink))` }}
    >
      <CategoryIcon name={icon} size={size === 'sm' ? 13 : 15} />
      {name}
    </span>
  );
}
