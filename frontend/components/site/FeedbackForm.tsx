'use client';
import { Camera, Star, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Button, Textarea } from '@/components/ui/form';
import { toast } from '@/components/ui/Toast';
import { toJpeg } from '@/lib/image-client';
import { MAX_FEEDBACK_MESSAGE, MAX_FEEDBACK_PHOTOS } from '@shared/feedback';

type Photo = { blob: Blob; url: string };

function StarPicker({ value, onChange }: { value: number; onChange: (n: number) => void }) {
  return (
    <div className="flex gap-1" role="radiogroup" aria-label="Nota de 1 a 5 estrelas">
      {[1, 2, 3, 4, 5].map((n) => (
        <button
          key={n}
          type="button"
          role="radio"
          aria-checked={value === n}
          aria-label={`${n} ${n === 1 ? 'estrela' : 'estrelas'}`}
          onClick={() => onChange(n)}
          className="p-1"
        >
          <Star size={32} className={n <= value ? 'text-pink' : 'text-line'} fill={n <= value ? 'currentColor' : 'none'} strokeWidth={n <= value ? 0 : 2} />
        </button>
      ))}
    </div>
  );
}

/** Área de feedback: nota (1-5, obrigatória), mensagem e fotos opcionais. Só o cliente logado consegue enviar
 *  (o form chama /api/cliente/feedback, que exige sessão); a confeiteira lê tudo pelo painel. */
export function FeedbackForm() {
  const [rating, setRating] = useState(0);
  const [message, setMessage] = useState('');
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);

  async function addFiles(files: FileList | null) {
    if (!files) return;
    const next = [...photos];
    for (const f of Array.from(files)) {
      if (next.length >= MAX_FEEDBACK_PHOTOS) { toast.error(`No máximo ${MAX_FEEDBACK_PHOTOS} fotos.`); break; }
      try { const blob = await toJpeg(f); next.push({ blob, url: URL.createObjectURL(blob) }); }
      catch (e: any) { toast.error(e.message); }
    }
    setPhotos(next);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!rating) return toast.error('Escolha uma nota de 1 a 5 estrelas.');
    const fd = new FormData();
    fd.set('rating', String(rating));
    fd.set('message', message.trim());
    photos.forEach((p, i) => fd.append('fotos', p.blob, `foto-${i + 1}.jpg`));
    setBusy(true);
    try {
      const res = await fetch('/api/cliente/feedback', { method: 'POST', body: fd }).catch(() => null);
      if (!res) throw new Error('Sem conexão com o servidor. Confira a internet e tente de novo.');
      if (res.status === 401) { location.href = `/cliente/entrar?next=${encodeURIComponent(location.pathname)}`; return; }
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error ?? 'Não foi possível enviar. Tente de novo.');
      setSent(true);
      toast.ok('Obrigado pelo feedback!');
    } catch (err: any) { toast.error(err.message); } finally { setBusy(false); }
  }

  if (sent) {
    return (
      <div className="rk-form rk-form--done" role="status">
        <h2>Obrigado pelo feedback!</h2>
        <p>Sua opinião ajuda a gente a melhorar sempre.</p>
      </div>
    );
  }

  return (
    <form onSubmit={submit} className="rk-form">
      <div>
        <h2>O que você achou?</h2>
        <p>Conte como foi sua experiência com a Raykookie.</p>
      </div>
      <StarPicker value={rating} onChange={setRating} />
      <Textarea
        placeholder="Escreva aqui (opcional)"
        maxLength={MAX_FEEDBACK_MESSAGE}
        value={message}
        onChange={(e) => setMessage(e.target.value)}
        rows={3}
      />
      <div>
        <p className="rk-form__label">Fotos (opcional, até {MAX_FEEDBACK_PHOTOS})</p>
        <ul className="mt-2 flex flex-wrap gap-2">
          {photos.map((p, i) => (
            <li key={p.url} className="relative">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={p.url} alt={`Foto ${i + 1}`} className="size-20 rounded-xl object-cover" />
              <button type="button" aria-label={`Remover foto ${i + 1}`} onClick={() => setPhotos(photos.filter((_, j) => j !== i))} className="absolute -right-2 -top-2 grid size-11 place-items-center rounded-full bg-ink text-on-pink"><Trash2 size={15} /></button>
            </li>
          ))}
          {photos.length < MAX_FEEDBACK_PHOTOS && (
            <li>
              <label className="grid size-20 cursor-pointer place-items-center rounded-xl bg-surface-2 text-muted transition hover:bg-line has-[:focus-visible]:outline has-[:focus-visible]:outline-[3px] has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-pink">
                <Camera size={22} /><span className="sr-only">Adicionar foto</span>
                <input type="file" accept="image/jpeg,image/png,image/webp" multiple capture="environment" className="sr-only" onChange={(e) => { void addFiles(e.target.files); e.target.value = ''; }} />
              </label>
            </li>
          )}
        </ul>
      </div>
      <Button type="submit" busy={busy}>Enviar feedback</Button>
    </form>
  );
}
