#!/usr/bin/env node
// Registra um APK JÁ COMPILADO (e assinado) pelo pipeline em downloads/ e atualiza downloads/manifest.json.
// É o par de infra/publish-apps.mjs (que compila na própria máquina); o manifesto tem o mesmo formato.
//
//   node infra/ci/publish-prebuilt.mjs --app client|server --apk ARQ.apk --version 1.0.0 --build 117 \
//        --sha256 <hex> [--commit SHA] [--api-base URL] [--downloads DIR]
//
// Garantias: confere o sha256 recebido; recusa build que não aumenta (o Android não atualiza por baixo);
// repetir a mesma publicação (mesmo build e mesmo sha256) não faz nada; escreve o manifesto de forma atômica;
// guarda só as 3 versões mais recentes de cada app.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const APPS = {
  client: { name: 'raykookie', fileBase: 'raykookie-cliente' },
  server: { name: 'raykookie business', fileBase: 'raykookie-gestao' },
};
export const KEEP = 3;

const sha256File = (f) => crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');

export function publishPrebuilt(o) {
  const cfg = APPS[o.app];
  if (!cfg) throw new Error(`app inválido: ${o.app} (use client ou server)`);
  if (!/^\d+\.\d+\.\d+$/.test(String(o.version))) throw new Error(`versão inválida: ${o.version} (use X.Y.Z)`);
  const build = Number(o.build);
  if (!Number.isInteger(build) || build < 1) throw new Error(`build inválido: ${o.build}`);
  if (!o.apk || !fs.existsSync(o.apk)) throw new Error(`APK não encontrado: ${o.apk}`);
  if (!/^[0-9a-f]{64}$/i.test(String(o.sha256 ?? ''))) throw new Error('informe --sha256 (64 hex) do APK gerado no build');
  const sum = sha256File(o.apk);
  if (sum !== String(o.sha256).toLowerCase()) throw new Error(`sha256 do APK não confere (esperado ${o.sha256}, veio ${sum}): arquivo corrompido ou trocado`);

  const downloads = path.resolve(o.downloads);
  fs.mkdirSync(downloads, { recursive: true });
  const manifestPath = path.join(downloads, 'manifest.json');
  let manifest = {};
  try { manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8')); } catch { /* primeira publicação */ }
  const current = manifest[o.app];

  const file = `${cfg.fileBase}-${o.version}+${build}.apk`;
  if (current && current.build === build) {
    if (current.sha256 === sum && fs.existsSync(path.join(downloads, current.file))) return { status: 'already', file: current.file, build };
    throw new Error(`o build ${build} já está publicado com outro conteúdo: use um número de build maior`);
  }
  if (current && build < current.build) throw new Error(`build ${build} é menor que o publicado (${current.build}): o Android não atualiza para trás`);

  // copia para um nome temporário e renomeia: quem baixa nunca pega arquivo pela metade
  const dest = path.join(downloads, file);
  fs.copyFileSync(o.apk, `${dest}.tmp`);
  fs.renameSync(`${dest}.tmp`, dest);
  manifest[o.app] = {
    app: o.app, name: cfg.name, version: o.version, build, file, size: fs.statSync(dest).size, sha256: sum,
    date: new Date().toISOString(), sourceHash: `ci:${o.commit ?? 'manual'}`, apiBase: o.apiBase ?? '',
  };
  fs.writeFileSync(`${manifestPath}.tmp`, JSON.stringify(manifest, null, 2) + '\n');
  fs.renameSync(`${manifestPath}.tmp`, manifestPath);

  // poda: só as KEEP mais recentes; o arquivo do manifesto nunca é apagado
  const mine = fs.readdirSync(downloads).filter((f) => f.startsWith(`${cfg.fileBase}-`) && f.endsWith('.apk'))
    .map((f) => ({ f, t: fs.statSync(path.join(downloads, f)).mtimeMs })).sort((a, b) => b.t - a.t);
  for (const old of mine.slice(KEEP)) if (old.f !== file) fs.rmSync(path.join(downloads, old.f));
  return { status: 'published', file, build };
}

function cli(argv) {
  const get = (n) => { const a = argv.find((x) => x.startsWith(`--${n}=`)); if (a) return a.split('=').slice(1).join('='); const i = argv.indexOf(`--${n}`); return i >= 0 ? argv[i + 1] : undefined; };
  const downloads = get('downloads') ?? process.env.DOWNLOADS_DIR ?? path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'downloads');
  try {
    const r = publishPrebuilt({ app: get('app'), apk: get('apk'), version: get('version'), build: get('build'), sha256: get('sha256'), commit: get('commit'), apiBase: get('api-base'), downloads });
    console.log(r.status === 'already' ? `✔ ${get('app')}: build ${r.build} já estava publicado (${r.file}).` : `✔ ${get('app')}: publicado ${r.file} (build ${r.build}).`);
  } catch (e) {
    console.error(`✖ ${e.message}`);
    process.exit(1);
  }
}
if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) cli(process.argv.slice(2));
