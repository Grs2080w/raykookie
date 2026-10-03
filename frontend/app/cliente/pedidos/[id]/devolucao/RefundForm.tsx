'use client';
import { Camera, Trash2 } from 'lucide-react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useMemo, useState } from 'react';
import { Button, Field, Select, Textarea } from '@/components/ui/form';
import { toast } from '@/components/ui/Toast';
import { toJpeg } from '@/lib/image-client';
import { money } from '@/lib/format';
import { MAX_REFUND_PHOTOS, MIN_REFUND_DESCRIPTION, REFUND_REASONS } from '@shared/refund';
import type { ShopOrder } from '@shared/types';

type Photo = { blob: Blob; url: string };

export function RefundForm({ order, api, backHref }: { order: ShopOrder; api?: string; backHref?: string }) {
  const router = useRouter();
  const [reasonId, setReasonId] = useState('');
  const [desc, setDesc] = useState('');
  const [qty, setQty] = useState<Record<string, number>>({});
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [agree, setAgree] = useState(false);
  const [busy, setBusy] = useState(false);
  const reason = REFUND_REASONS.find((r) => r.id === reasonId);
  const needPhoto = order.refund.photos_required && reason?.photos === 'sim';
  const showPhotos = reason ? reason.photos !== 'nao' || photos.length > 0 : false;
  const amount = useMemo(() => Math.min(order.refund.max_amount, order.items.reduce((s, i) => s + (qty[i.id] ?? 0) * i.unit_price, 0)), [qty, order]);
  useEffect(() => () => photos.forEach((p) => URL.revokeObjectURL(p.url)), []); // eslint-disable-line react-hooks/exhaustive-deps

  async function addFiles(files: FileList | null) {
    if (!files) return;
    const next = [...photos];
    for (const f of Array.from(files)) {
      if (next.length >= MAX_REFUND_PHOTOS) { toast.error(`No máximo ${MAX_REFUND_PHOTOS} fotos.`); break; }
      try { const blob = await toJpeg(f); next.push({ blob, url: URL.createObjectURL(blob) }); } // reduz e re-codifica: some a localização escondida na foto
      catch (e: any) { toast.error(e.message); }
    }
    setPhotos(next);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const items = order.items.filter((i) => (qty[i.id] ?? 0) > 0).map((i) => ({ id: i.id, qty: qty[i.id] }));
    if (!reason) return toast.error('Escolha o motivo.');
    if (!items.length) return toast.error('Marque pelo menos um item com problema.');
    if (desc.trim().length < MIN_REFUND_DESCRIPTION) return toast.error(`Conte o que aconteceu (pelo menos ${MIN_REFUND_DESCRIPTION} caracteres).`);
    if (needPhoto && !photos.length) return toast.error('Envie pelo menos uma foto para mostrar o problema.');
    if (!agree) return toast.error('Confirme que entendeu como funciona a análise.');
    const fd = new FormData();
    fd.set('reason_code', reason.id); fd.set('description', desc); fd.set('items', JSON.stringify(items));
    photos.forEach((p, i) => fd.append('fotos', p.blob, `foto-${i + 1}.jpg`));
    setBusy(true);
    try {
      const res = await fetch(api ?? `/api/cliente/pedidos/${order.id}/devolucao`, { method: 'POST', body: fd }).catch(() => null);
      if (!res) throw new Error('Sem conexão com o servidor. Confira a internet e tente de novo.');
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error ?? 'Não foi possível enviar. Tente de novo.');
      toast.ok('Solicitação enviada');
      router.replace(backHref ?? '/cliente/pedidos'); router.refresh();
    } catch (err: any) { toast.error(err.message); } finally { setBusy(false); }
  }

  return (
    <form onSubmit={submit} className="mt-5 grid gap-5">
      <Field label="Qual foi o problema?">{(id) => (
        <Select id={id} value={reasonId} onChange={(e) => setReasonId(e.target.value)} required>
          <option value="">Escolha o motivo</option>
          {REFUND_REASONS.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}
        </Select>
      )}</Field>
      {reason?.hint && <p role="note" className="rounded-xl bg-warn/10 p-3 text-sm">{reason.hint}</p>}

      <fieldset>
        <legend className="text-sm font-medium">Quais itens tiveram o problema?</legend>
        <ul className="mt-2 grid gap-2">
          {order.items.map((i) => (
            <li key={i.id} className="flex items-center justify-between gap-3 rounded-xl border border-line bg-white p-3">
              <span className="text-[15px]">{i.name} <span className="text-muted">({i.qty} no pedido · {money(i.unit_price)} cada)</span></span>
              <select aria-label={`Unidades com problema: ${i.name}`} value={qty[i.id] ?? 0} onChange={(e) => setQty({ ...qty, [i.id]: Number(e.target.value) })} className="min-h-11 rounded-lg border border-line bg-white px-2">
                {Array.from({ length: i.qty + 1 }, (_, n) => <option key={n} value={n}>{n}</option>)}
              </select>
            </li>
          ))}
        </ul>
        <p className="mt-2 text-sm">Valor que você está pedindo de volta: <b className="tnum">{money(amount)}</b></p>
      </fieldset>

      <Field label="Conte o que aconteceu" hint={`Mínimo de ${MIN_REFUND_DESCRIPTION} caracteres. Quanto mais detalhes, mais rápida a análise.`}>{(id) => (
        <Textarea id={id} value={desc} onChange={(e) => setDesc(e.target.value)} rows={4} maxLength={1000} required />
      )}</Field>

      {showPhotos && (
        <div>
          <p className="text-sm font-medium">Fotos {needPhoto ? '(obrigatório: de 1 a 5)' : '(opcional, até 5)'}</p>
          <p className="text-xs text-muted">Mostre o produto e a embalagem. Só a confeiteira vê as fotos, e a localização escondida nelas é removida.</p>
          <ul className="mt-2 flex flex-wrap gap-2">
            {photos.map((p, i) => (
              <li key={p.url} className="relative">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={p.url} alt={`Foto ${i + 1}`} className="size-20 rounded-xl border border-line object-cover" />
                <button type="button" aria-label={`Remover foto ${i + 1}`} onClick={() => setPhotos(photos.filter((_, j) => j !== i))} className="absolute -right-2 -top-2 grid size-8 place-items-center rounded-full bg-bad text-white"><Trash2 size={14} /></button>
              </li>
            ))}
            {photos.length < MAX_REFUND_PHOTOS && (
              <li>
                <label className="grid size-20 cursor-pointer place-items-center rounded-xl border-2 border-dashed border-line text-muted hover:border-pink">
                  <Camera size={22} /><span className="sr-only">Adicionar foto</span>
                  <input type="file" accept="image/jpeg,image/png,image/webp" multiple capture="environment" className="sr-only" onChange={(e) => { void addFiles(e.target.files); e.target.value = ''; }} />
                </label>
              </li>
            )}
          </ul>
        </div>
      )}

      <label className="flex items-start gap-2 text-sm">
        <input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} className="mt-1 size-5 accent-[var(--pink)]" />
        <span>Entendo que, como o pedido já entrou em produção, a devolução <b>não é garantida</b>: a confeiteira analisa e decide, podendo devolver o valor total, parte dele ou negar. Veja os <Link href="/termos#s6" className="font-semibold text-pink-strong underline">Termos</Link>.</span>
      </label>
      <Button type="submit" busy={busy} className="min-h-12 w-full">Enviar solicitação</Button>
    </form>
  );
}
