'use client';
import { Star } from 'lucide-react';
import { useState } from 'react';
import { Card } from '@/components/ui/form';

export type FeedbackRow = { id: number; rating: number; message: string; created_at: string; customer_name: string; customer_email: string; photos: number };

const when = (iso: string) => new Date(iso).toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo', day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });

function Stars({ rating }: { rating: number }) {
  return (
    <div className="flex items-center gap-0.5" aria-label={`${rating} de 5 estrelas`}>
      {Array.from({ length: 5 }, (_, i) => (
        <Star key={i} size={16} className={i < rating ? 'fill-warn text-warn' : 'text-line'} />
      ))}
    </div>
  );
}

export function FeedbackList({ list }: { list: FeedbackRow[] }) {
  const [zoom, setZoom] = useState<string | null>(null);
  return (
    <>
      <ul className="grid gap-3">
        {list.map((f) => (
          <li key={f.id}>
            <Card className="p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <Stars rating={f.rating} />
                <span className="text-sm text-muted">{when(f.created_at)}</span>
              </div>
              <p className="mt-2 font-bold">{f.customer_name}</p>
              <p className="text-sm text-muted">{f.customer_email}</p>
              {f.message && <p className="mt-2 text-sm">{f.message}</p>}
              {f.photos > 0 && (
                <ul className="mt-3 flex flex-wrap gap-2" aria-label="Fotos enviadas">
                  {Array.from({ length: f.photos }, (_, i) => i + 1).map((n) => {
                    const url = `/api/feedback/${f.id}/fotos/${n}`;
                    return (
                      <li key={n}>
                        <button type="button" onClick={() => setZoom(url)} aria-label={`Ampliar foto ${n}`}>
                          {/* eslint-disable-next-line @next/next/no-img-element */}
                          <img src={url} alt={`Foto ${n} enviada por ${f.customer_name}`} className="size-24 rounded-xl border border-line object-cover" />
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </Card>
          </li>
        ))}
      </ul>

      {zoom && (
        <div role="dialog" aria-label="Foto ampliada" className="fixed inset-0 z-[100] grid place-items-center bg-black/80 p-4" onClick={() => setZoom(null)}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={zoom} alt="Foto ampliada" className="max-h-full max-w-full rounded-xl" />
        </div>
      )}
    </>
  );
}
