import { sniffImage, stripJpegMetadata } from '@/lib/image';
import type { RequestInput } from '@/lib/refunds';
import { MAX_REFUND_PHOTOS, MAX_REFUND_PHOTO_BYTES } from '@shared/refund';

const MAX_REFUND_FORM_BYTES = 12 * 1024 * 1024;

/** Lê o multipart do pedido de devolução (reason_code, description, items em JSON e até 5 fotos "fotos"): valida e limpa as fotos. */
export async function readRefundForm(req: Request): Promise<{ ok: true; input: RequestInput } | { ok: false; status: number; error: string }> {
  if (Number(req.headers.get('content-length')) > MAX_REFUND_FORM_BYTES) return { ok: false, status: 413, error: 'O envio é grande demais (máximo de 12 MB no total).' };
  let form: FormData;
  try { form = await req.formData(); } catch { return { ok: false, status: 400, error: 'Envie o pedido de devolução pelo aplicativo ou pelo site.' }; }
  const files = form.getAll('fotos').filter((f): f is File => typeof f !== 'string' && f.size > 0);
  if (files.length > MAX_REFUND_PHOTOS) return { ok: false, status: 400, error: `Envie no máximo ${MAX_REFUND_PHOTOS} fotos.` };
  const photos: { type: string; data: Buffer }[] = [];
  for (const f of files) {
    if (f.size > MAX_REFUND_PHOTO_BYTES) return { ok: false, status: 413, error: 'Uma das fotos é grande demais (máximo de 2 MB).' };
    const data = Buffer.from(await f.arrayBuffer());
    const type = sniffImage(data);
    if (!type) return { ok: false, status: 415, error: 'Use fotos JPG, PNG ou WebP.' };
    photos.push({ type, data: type === 'image/jpeg' ? stripJpegMetadata(data) : data }); // sem EXIF/localização
  }
  let items: unknown = [];
  try { items = JSON.parse(String(form.get('items') ?? '[]')); } catch { return { ok: false, status: 400, error: 'Confira os itens marcados.' }; }
  return { ok: true, input: { reason_code: form.get('reason_code'), description: form.get('description'), items, photos } };
}
