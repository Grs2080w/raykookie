'use client';
// Reduz a foto no navegador (lado maior 1200 px, JPEG) antes de enviar: upload rápido e banco leve.
export async function toJpeg(file: File, max = 1200): Promise<Blob> {
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' }).catch(() => null);
  if (!bmp) throw new Error('Não consegui abrir essa imagem. Use uma foto JPG, PNG ou WebP.');
  const scale = Math.min(1, max / Math.max(bmp.width, bmp.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bmp.width * scale); canvas.height = Math.round(bmp.height * scale);
  const ctx = canvas.getContext('2d')!;
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, canvas.width, canvas.height); // PNG transparente vira fundo branco
  ctx.drawImage(bmp, 0, 0, canvas.width, canvas.height);
  bmp.close();
  const blob = await new Promise<Blob | null>((r) => canvas.toBlob(r, 'image/jpeg', 0.82));
  if (!blob) throw new Error('Não consegui preparar a foto. Tente outra imagem.');
  return blob;
}

export async function uploadRecipePhoto(recipeId: number, blob: Blob) {
  const res = await fetch(`/api/recipes/${recipeId}/photo`, { method: 'PUT', headers: { 'content-type': blob.type || 'image/jpeg' }, body: blob }).catch(() => null);
  if (!res) throw new Error('Sem conexão com o servidor. Confira a internet e tente de novo.');
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error ?? 'Não foi possível enviar a foto.');
}
