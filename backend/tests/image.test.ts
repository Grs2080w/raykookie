import { describe, expect, it } from 'vitest';
import { sniffImage, stripJpegMetadata } from '@/lib/image';

const seg = (marker: number, payload: Buffer) => Buffer.concat([Buffer.from([0xff, marker]), Buffer.from([(payload.length + 2) >> 8, (payload.length + 2) & 0xff]), payload]);
const jpeg = (...segs: Buffer[]) => Buffer.concat([Buffer.from([0xff, 0xd8]), ...segs, Buffer.from([0xff, 0xda, 0x00, 0x04, 0x00, 0x00, 1, 2, 3, 4, 0xff, 0xd9])]);

describe('imagens', () => {
  it('reconhece o tipo pelo conteúdo', () => {
    expect(sniffImage(jpeg(seg(0xe0, Buffer.from('JFIF\0'))))).toBe('image/jpeg');
    expect(sniffImage(Buffer.from('GIF89a-------------'))).toBeNull();
    expect(sniffImage(Buffer.from('<script>alert(1)</script>'))).toBeNull();
  });
  it('tira EXIF (com GPS), XMP e comentário do JPEG, e mantém o resto da imagem', () => {
    const exif = seg(0xe1, Buffer.from('Exif\0\0GPS-LATITUDE-23.5'));
    const xmp = seg(0xe1, Buffer.from('http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>'));
    const com = seg(0xfe, Buffer.from('tirada em casa'));
    const jfif = seg(0xe0, Buffer.from('JFIF\0\x01\x01'));
    const dqt = seg(0xdb, Buffer.alloc(10, 7));
    const out = stripJpegMetadata(jpeg(jfif, exif, xmp, com, dqt));
    expect(out.toString('latin1')).not.toMatch(/GPS|Exif|xmpmeta|casa/);
    expect(out.includes(jfif)).toBe(true);
    expect(out.includes(dqt)).toBe(true);
    expect(out.subarray(out.length - 2).equals(Buffer.from([0xff, 0xd9]))).toBe(true);
    expect(sniffImage(out)).toBe('image/jpeg');
  });
  it('arquivo estranho volta como veio', () => {
    const odd = Buffer.from([0xff, 0xd8, 0x00, 0x01, 0x02, 0x03, 0x04]);
    expect(stripJpegMetadata(odd)).toBe(odd);
  });
});
