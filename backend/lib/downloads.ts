// Publicação dos apps Android (APK): manifesto de versões + arquivos em <raiz do repositório>/downloads.
// Quem grava os arquivos é infra/publish-apps.mjs; aqui só lemos e entregamos.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import type { AppId, AppRelease, AppReleases } from '@shared/types';

export const APPS: AppId[] = ['client', 'server'];
export const isAppId = (v: string): v is AppId => (APPS as string[]).includes(v);

export const downloadsDir = () => path.resolve(process.env.DOWNLOADS_DIR || path.join(process.cwd(), '..', 'downloads'));

export function readManifest(): AppReleases {
  try {
    const raw = JSON.parse(fs.readFileSync(path.join(downloadsDir(), 'manifest.json'), 'utf8'));
    const out: AppReleases = {};
    for (const a of APPS) {
      const r = raw?.[a];
      // o arquivo precisa existir e ter nome seguro (só o que o script de publicação gera)
      if (r && /^[\w.+-]+\.apk$/.test(String(r.file)) && fs.existsSync(path.join(downloadsDir(), r.file))) out[a] = r as AppRelease;
    }
    return out;
  } catch { return {}; }
}

export const releaseFilePath = (r: AppRelease) => path.join(downloadsDir(), r.file);

// ---- link temporário assinado (o app abre o download no navegador, que não tem o token da sessão) --------
const secret = () => process.env.DOWNLOAD_SECRET || `rayka-download:${process.env.DATABASE_URL ?? 'dev'}`;
const mac = (app: AppId, exp: number) => crypto.createHmac('sha256', secret()).update(`${app}.${exp}`).digest('base64url');

export function signDownload(app: AppId, ttlSec = 300) {
  const exp = Math.floor(Date.now() / 1000) + ttlSec;
  return `${exp}.${mac(app, exp)}`;
}
export function verifyDownload(app: AppId, token: string | null): boolean {
  const [expS, sig] = String(token ?? '').split('.');
  const exp = Number(expS);
  if (!sig || !Number.isFinite(exp) || exp < Date.now() / 1000) return false;
  const good = mac(app, exp);
  return sig.length === good.length && crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(good));
}
