import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { readManifest, signDownload, verifyDownload } from '@/lib/downloads';

let dir: string;
beforeAll(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dl-'));
  process.env.DOWNLOADS_DIR = dir;
  fs.writeFileSync(path.join(dir, 'raykookie-1.0.0+3.apk'), 'apk');
  fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify({
    client: { app: 'client', name: 'raykookie', version: '1.0.0', build: 3, file: 'raykookie-1.0.0+3.apk', size: 3, sha256: 'x', date: '2026-10-01' },
    server: { app: 'server', name: 'gestão', version: '1.0.0', build: 1, file: '../../etc/passwd', size: 1, sha256: 'y', date: '2026-10-01' },
  }));
});
afterAll(() => fs.rmSync(dir, { recursive: true, force: true }));

describe('manifesto de downloads', () => {
  it('lista só o que tem arquivo válido; recusa nome de arquivo com caminho', () => {
    const m = readManifest();
    expect(m.client?.version).toBe('1.0.0');
    expect(m.server).toBeUndefined(); // "../../etc/passwd" não é aceito
  });
  it('sem manifesto devolve vazio', () => {
    process.env.DOWNLOADS_DIR = path.join(dir, 'nao-existe');
    expect(readManifest()).toEqual({});
    process.env.DOWNLOADS_DIR = dir;
  });
});

describe('link assinado', () => {
  it('vale para o app certo e dentro do prazo', () => {
    const t = signDownload('server', 60);
    expect(verifyDownload('server', t)).toBe(true);
    expect(verifyDownload('client', t)).toBe(false);
  });
  it('recusa adulterado, expirado e vazio', () => {
    const t = signDownload('server', 60);
    expect(verifyDownload('server', t.slice(0, -2) + 'xx')).toBe(false);
    expect(verifyDownload('server', signDownload('server', -10))).toBe(false);
    expect(verifyDownload('server', null)).toBe(false);
    expect(verifyDownload('server', 'lixo')).toBe(false);
  });
});
