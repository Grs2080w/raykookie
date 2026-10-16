'use client';
import { useState } from 'react';

export type Stage = { name: string; text: string; photo: string; alt: string };

/** Acordeão horizontal do "Como funciona": a etapa aberta ocupa a maior parte; as outras viram tiras com o nome em pé. */
export function HowItWorks({ stages }: { stages: Stage[] }) {
  const [open, setOpen] = useState(0);
  return (
    <div className="acc" data-reveal style={{ '--i': 2 } as React.CSSProperties}>
      {stages.map((s, i) => (
        <div
          key={s.name}
          className="acc-panel"
          data-open={open === i}
          role="button"
          tabIndex={open === i ? -1 : 0}
          aria-expanded={open === i}
          aria-label={open === i ? undefined : `Abrir a etapa ${s.name}`}
          onClick={() => setOpen(i)}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setOpen(i); } }}
        >
          <span className="acc-sliver" aria-hidden={open === i}>{s.name}</span>
          <div className="acc-body" aria-hidden={open !== i}>
            <div className="acc-text">
              <h3 className="display">{s.name}</h3>
              <p>{s.text}</p>
            </div>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <div className="disc-photo bite"><img src={s.photo} alt={s.alt} width={600} height={600} loading="lazy" /></div>
          </div>
        </div>
      ))}
    </div>
  );
}
