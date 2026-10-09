export function PageHeader({ title, subtitle, action }: { title: string; subtitle?: string; action?: React.ReactNode }) {
  return (
    <div className="mb-7 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="font-display text-[34px] font-semibold leading-tight md:text-[40px]">{title}</h1>
        {subtitle && <p className="mt-1 max-w-xl text-muted">{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}

export function EmptyState({ icon, title, text, action }: { icon: React.ReactNode; title: string; text: string; action?: React.ReactNode }) {
  return (
    <div className="flex flex-col items-center rounded-3xl border border-dashed border-line px-6 py-14 text-center">
      <span className="grid size-14 place-items-center rounded-2xl bg-pink-tint text-pink-strong">{icon}</span>
      <h3 className="mt-4 font-display text-xl font-semibold">{title}</h3>
      <p className="mt-1 max-w-sm text-sm text-muted">{text}</p>
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}
