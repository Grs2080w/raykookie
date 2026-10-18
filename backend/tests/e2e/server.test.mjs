// Testes de fumaça do backend/server.mjs: sobe o processo de verdade (com os builds de backend/ e frontend/)
// e confere roteamento, saúde, manifesto de downloads e desligamento.
//   cd backend && npm run test:e2e   (modo API + site: defina FRONTEND_DIR_UNDER_TEST=<pasta do frontend com build>)
// Nunca conecta no banco de produção: DATABASE_URL aponta para uma porta fechada, e o ambiente do processo
// é montado do zero (não herda o seu). Opcional, só leitura: SERVER_URL=https://api.raykookie.com.br
// roda também as checagens contra um servidor já no ar.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { after, before, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';

const backendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const backendDir = path.resolve(process.env.BACKEND_DIR || backendRoot);
const frontendDir = process.env.FRONTEND_DIR_UNDER_TEST ? path.resolve(process.env.FRONTEND_DIR_UNDER_TEST) : null;
const hasBackendBuild = fs.existsSync(path.join(backendDir, '.next', 'BUILD_ID'));
const hasFrontendBuild = !!frontendDir && fs.existsSync(path.join(frontendDir, '.next', 'BUILD_ID'));
const NO_BACKEND = 'sem build do backend (rode npm run build em backend/)';
const NO_FRONTEND = frontendDir
  ? 'sem build do frontend (rode npm run build em frontend/)'
  : 'defina FRONTEND_DIR_UNDER_TEST para testar o modo API + site';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'rayka-server-'));

const freePort = () => new Promise((resolve, reject) => {
  const s = net.createServer().once('error', reject);
  s.listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => resolve(port)); });
});

// a API só lista um app se o APK citado no manifesto existe na pasta
const fixtureDownloads = ({ withApks = true } = {}) => {
  const dir = tmp();
  const entry = (app, name, build) => ({ app, name, version: '1.0.0', build, file: `${app}.apk`, size: 1, sha256: 'a'.repeat(64), date: '2026-01-01T00:00:00.000Z' });
  fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify({ client: entry('client', 'raykookie', 7), server: entry('server', 'raykookie gestão', 9) }));
  if (withApks) for (const app of ['client', 'server']) fs.writeFileSync(path.join(dir, `${app}.apk`), 'x');
  return dir;
};

async function startServer(extraEnv = {}) {
  const port = await freePort();
  const env = {
    PATH: process.env.PATH,
    HOME: process.env.HOME,
    NODE_ENV: 'production',
    HOST: '127.0.0.1',
    PORT: String(port),
    // porta 9 (discard) fechada: qualquer consulta falha na hora, e o .env local (se houver) não sobrepõe
    DATABASE_URL: 'postgres://teste:teste@127.0.0.1:9/teste',
    JOBS_SECRET: '',
    FIREBASE_SERVICE_ACCOUNT_PATH: '',
    NEXT_TELEMETRY_DISABLED: '1',
    ...extraEnv,
  };
  const child = spawn(process.execPath, ['server.mjs'], { cwd: backendDir, env, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  const exited = new Promise((resolve) => child.once('exit', (code, signal) => resolve({ code, signal })));
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`servidor não subiu em 60s:\n${output}`)), 60_000);
    const onData = (d) => { output += d; if (output.includes('no ar em')) { clearTimeout(timer); resolve(); } };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    exited.then(({ code }) => { clearTimeout(timer); reject(new Error(`servidor saiu com código ${code}:\n${output}`)); });
  });
  return {
    base: `http://127.0.0.1:${port}`,
    output: () => output,
    exited,
    async stop() { if (child.exitCode === null) child.kill('SIGTERM'); return exited; },
  };
}

const getJson = async (url) => {
  const res = await fetch(url);
  assert.match(res.headers.get('content-type') ?? '', /application\/json/, `${url} deveria responder JSON`);
  return { res, body: await res.json() };
};

test('sem o build do backend, sai com código 1 e explica o que falta', { skip: !hasBackendBuild && NO_BACKEND }, async () => {
  const port = await freePort();
  const child = spawn(process.execPath, ['server.mjs'], {
    cwd: backendDir,
    env: { PATH: process.env.PATH, PORT: String(port), HOST: '127.0.0.1', NEXT_DIST_DIR: '.build-que-nao-existe' },
  });
  let err = '';
  child.stderr.on('data', (d) => { err += d; });
  const code = await new Promise((resolve) => child.once('exit', resolve));
  assert.equal(code, 1);
  assert.match(err, /Falta o build do backend/);
});

describe('só a API (como na imagem Docker de produção)', { skip: !hasBackendBuild && NO_BACKEND }, () => {
  let srv;
  before(async () => { srv = await startServer({ FRONTEND_DIR: tmp(), DOWNLOADS_DIR: fixtureDownloads() }); });
  after(async () => { await srv?.stop(); });

  test('avisa no log que está sem site', () => {
    assert.match(srv.output(), /sem build do frontend/);
    assert.match(srv.output(), /\(API \+ sem site\)/);
  });

  test('/api/health responde 503 em JSON quando o banco está fora', async () => {
    const { res, body } = await getJson(`${srv.base}/api/health`);
    assert.equal(res.status, 503);
    assert.deepEqual(body, { ok: false });
  });

  test('/api/downloads mostra só o app do cliente para quem não está logado, sem cache', async () => {
    const { res, body } = await getJson(`${srv.base}/api/downloads`);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('cache-control'), 'no-store');
    assert.equal(body.client.build, 7);
    assert.equal(body.client.url, '/api/downloads/client');
    assert.equal(body.server, undefined, 'o APK de gestão não pode aparecer sem login');
  });

  test('/api/downloads esconde o app de gestão mesmo com um token de sessão inválido', async () => {
    const res = await fetch(`${srv.base}/api/downloads`, { headers: { authorization: 'Bearer token-falso' } });
    // com o banco fora a sessão não pode ser validada: nunca pode "vazar" o app de gestão
    if (res.status === 200) assert.equal((await res.json()).server, undefined);
    else assert.ok(res.status >= 400);
  });

  test('sem site, qualquer caminho cai na API (não há página na raiz)', async () => {
    const res = await fetch(`${srv.base}/`);
    assert.equal(res.status, 404);
    const fav = await fetch(`${srv.base}/favicon.svg`);
    assert.equal(fav.status, 404);
  });

  test('rota de API inexistente responde 404', async () => {
    const res = await fetch(`${srv.base}/api/rota-que-nao-existe`);
    assert.equal(res.status, 404);
  });
});

describe('API + site no mesmo processo', { skip: (!hasBackendBuild && NO_BACKEND) || (!hasFrontendBuild && NO_FRONTEND) }, () => {
  let srv;
  before(async () => { srv = await startServer({ FRONTEND_DIR: frontendDir, DOWNLOADS_DIR: fixtureDownloads() }); });
  after(async () => { await srv?.stop(); });

  test('anuncia que está servindo o site', () => {
    assert.match(srv.output(), /\(API \+ site\)/);
  });

  test('arquivos públicos do frontend são servidos pelo site', async () => {
    const res = await fetch(`${srv.base}/favicon.svg`);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type') ?? '', /svg/);
  });

  test('/api/* continua indo para a API', async () => {
    const health = await getJson(`${srv.base}/api/health`);
    assert.equal(health.res.status, 503);
    const dl = await getJson(`${srv.base}/api/downloads?x=1`);
    assert.equal(dl.body.client.build, 7);
  });

  test('caminho que só começa com "api" (sem barra) vai para o site, não para a API', async () => {
    const viaSite = await fetch(`${srv.base}/apifavicon.svg`);
    const viaApi = await fetch(`${srv.base}/api/favicon.svg`);
    assert.equal(viaApi.status, 404);
    // o site responde com a página dele (404 ou não), nunca com JSON da API
    assert.doesNotMatch(viaSite.headers.get('content-type') ?? '', /application\/json/);
  });
});

test('/api/downloads não anuncia app cujo APK sumiu da pasta', { skip: !hasBackendBuild && NO_BACKEND }, async () => {
  const srv = await startServer({ FRONTEND_DIR: tmp(), DOWNLOADS_DIR: fixtureDownloads({ withApks: false }) });
  try {
    const { res, body } = await getJson(`${srv.base}/api/downloads`);
    assert.equal(res.status, 200);
    assert.deepEqual(body, {});
  } finally { await srv.stop(); }
});

test('SIGTERM encerra o servidor com código 0', { skip: !hasBackendBuild && NO_BACKEND }, async () => {
  const srv = await startServer({ FRONTEND_DIR: tmp(), DOWNLOADS_DIR: fixtureDownloads() });
  const started = Date.now();
  const { code } = await srv.stop();
  assert.equal(code, 0);
  assert.ok(Date.now() - started < 10_000, 'demorou demais para desligar');
});

describe('servidor no ar (só leitura)', { skip: !process.env.SERVER_URL && 'defina SERVER_URL para checar um servidor já no ar' }, () => {
  const base = (process.env.SERVER_URL ?? '').replace(/\/$/, '');

  test('/api/health: API e banco de pé', async () => {
    const { res, body } = await getJson(`${base}/api/health`);
    assert.equal(res.status, 200);
    assert.deepEqual(body, { ok: true });
  });

  test('/api/downloads: manifesto lido e app de gestão escondido sem login', async () => {
    const { res, body } = await getJson(`${base}/api/downloads`);
    assert.equal(res.status, 200);
    assert.equal(typeof body.client?.build, 'number');
    assert.equal(body.server, undefined);
  });

  test('/api/menu: cardápio público sem dados internos da receita', async () => {
    const { res, body } = await getJson(`${base}/api/menu`);
    assert.equal(res.status, 200);
    assert.ok(Array.isArray(body.items) && Array.isArray(body.groups));
    const text = JSON.stringify(body);
    for (const leak of ['ingredients', 'cost', 'margin', 'custo', 'margem']) assert.ok(!text.includes(`"${leak}"`), `cardápio expôs "${leak}"`);
  });
});
