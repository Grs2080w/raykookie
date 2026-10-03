// Confere o tipo da imagem pelo conteúdo do arquivo, não pelo que o navegador ou o app dizem.
export function sniffImage(b: Buffer): 'image/jpeg' | 'image/png' | 'image/webp' | null {
  if (b.length > 12 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg';
  if (b.length > 12 && b.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'image/png';
  if (b.length > 12 && b.subarray(0, 4).toString() === 'RIFF' && b.subarray(8, 12).toString() === 'WEBP') return 'image/webp';
  return null;
}

/** Tira os metadados escondidos de um JPEG (EXIF com localização, XMP, comentários), mantendo a imagem.
 *  Percorre os segmentos até o início da imagem e descarta APP1..APP15 e COM; APP0 (JFIF) e APP14 (Adobe, cores) ficam. */
export function stripJpegMetadata(b: Buffer): Buffer {
  if (!(b.length > 4 && b[0] === 0xff && b[1] === 0xd8)) return b;
  const out: Buffer[] = [b.subarray(0, 2)];
  let i = 2;
  while (i + 4 <= b.length) {
    if (b[i] !== 0xff) return b; // estrutura inesperada: devolve como veio
    const marker = b[i + 1];
    if (marker === 0xff) { i++; continue; } // preenchimento
    if (marker === 0xda || marker === 0xd9) { out.push(b.subarray(i)); return Buffer.concat(out); } // início dos dados da imagem
    const len = b.readUInt16BE(i + 2);
    if (len < 2 || i + 2 + len > b.length) return b;
    const drop = (marker >= 0xe1 && marker <= 0xef && marker !== 0xee) || marker === 0xfe;
    if (!drop) out.push(b.subarray(i, i + 2 + len));
    i += 2 + len;
  }
  return b;
}
