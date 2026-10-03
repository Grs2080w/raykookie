// node --test infra/ci
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { publishPrebuilt } from './publish-prebuilt.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'pp-'));
const apk = (dir, content) => { const f = path.join(dir, `in-${Math.random().toString(36).slice(2)}.apk`); fs.writeFileSync(f, content); return { f, sha: crypto.createHash('sha256').update(content).digest('hex') }; };
const pub = (d, a, o = {}) => publishPrebuilt({ app: 'client', version: '1.0.0', build: 10, apk: a.f, sha256: a.sha, downloads: path.join(d, 'dl'), commit: 'abc', apiBase: 'https://x', ...o });
const manifest = (d) => JSON.parse(fs.readFileSync(path.join(d, 'dl', 'manifest.json'), 'utf8'));

test('primeira publicação: copia o APK e grava o manifesto no formato do site', () => {
  const d = tmp(); const a = apk(d, 'um');
  assert.deepEqual(pub(d, a), { status: 'published', file: 'raykookie-cliente-1.0.0+10.apk', build: 10 });
  const m = manifest(d).client;
  assert.equal(m.build, 10); assert.equal(m.sha256, a.sha); assert.equal(m.size, 2); assert.equal(m.sourceHash, 'ci:abc'); assert.equal(m.name, 'raykookie');
  assert.ok(fs.existsSync(path.join(d, 'dl', m.file)));
  assert.ok(!fs.readdirSync(path.join(d, 'dl')).some((f) => f.endsWith('.tmp')));
});

test('sha256 que não confere é recusado e nada é publicado', () => {
  const d = tmp(); const a = apk(d, 'um');
  assert.throws(() => pub(d, a, { sha256: 'a'.repeat(64) }), /não confere/);
  assert.ok(!fs.existsSync(path.join(d, 'dl', 'manifest.json')));
});

test('build menor ou igual ao publicado é recusado; repetir a mesma publicação é inofensivo', () => {
  const d = tmp(); const a = apk(d, 'um'); const b = apk(d, 'dois');
  pub(d, a);
  assert.equal(pub(d, a).status, 'already');
  assert.throws(() => pub(d, b, { build: 10 }), /outro conteúdo/);
  assert.throws(() => pub(d, b, { build: 9 }), /menor que o publicado/);
  assert.equal(manifest(d).client.sha256, a.sha);
});

test('guarda só as 3 versões mais recentes por app e nunca apaga a do manifesto', () => {
  const d = tmp();
  for (const n of [10, 11, 12, 13, 14]) {
    pub(d, apk(d, `v${n}`), { build: n });
    const f = path.join(d, 'dl', `raykookie-cliente-1.0.0+${n}.apk`);
    fs.utimesSync(f, new Date(2026, 0, n), new Date(2026, 0, n));
  }
  const left = fs.readdirSync(path.join(d, 'dl')).filter((f) => f.endsWith('.apk')).sort();
  assert.deepEqual(left, ['raykookie-cliente-1.0.0+12.apk', 'raykookie-cliente-1.0.0+13.apk', 'raykookie-cliente-1.0.0+14.apk']);
  assert.equal(manifest(d).client.build, 14);
});

test('os dois apps convivem no mesmo manifesto', () => {
  const d = tmp();
  pub(d, apk(d, 'c'), { app: 'client', build: 5 });
  pub(d, apk(d, 's'), { app: 'server', build: 7, version: '1.1.0' });
  const m = manifest(d);
  assert.equal(m.client.build, 5); assert.equal(m.server.build, 7); assert.equal(m.server.file, 'raykookie-gestao-1.1.0+7.apk');
});

test('entradas inválidas', () => {
  const d = tmp(); const a = apk(d, 'um');
  assert.throws(() => pub(d, a, { app: 'web' }), /app inválido/);
  assert.throws(() => pub(d, a, { version: '1.0' }), /versão inválida/);
  assert.throws(() => pub(d, a, { build: 'x' }), /build inválido/);
  assert.throws(() => pub(d, a, { apk: '/nao/existe.apk' }), /não encontrado/);
  assert.throws(() => pub(d, a, { sha256: 'zz' }), /--sha256/);
});

test('CLI: sai com código 1 e mensagem clara no erro, 0 no sucesso', () => {
  const d = tmp(); const a = apk(d, 'um');
  const ok = spawnSync('node', [path.join(here, 'publish-prebuilt.mjs'), '--app', 'server', '--apk', a.f, '--version', '2.0.0', '--build', '3', '--sha256', a.sha, '--downloads', path.join(d, 'dl')], { encoding: 'utf8' });
  assert.equal(ok.status, 0, ok.stderr); assert.match(ok.stdout, /publicado raykookie-gestao-2\.0\.0\+3\.apk/);
  const bad = spawnSync('node', [path.join(here, 'publish-prebuilt.mjs'), '--app', 'server', '--apk', a.f, '--version', '2.0.0', '--build', '2', '--sha256', a.sha, '--downloads', path.join(d, 'dl')], { encoding: 'utf8' });
  assert.equal(bad.status, 1); assert.match(bad.stderr, /menor que o publicado/);
});
