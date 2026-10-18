#!/usr/bin/env node
// Publica os apps Android (APK) em downloads/ e atualiza downloads/manifest.json.
//
//   node infra/publish-apps.mjs [client|server|all] [--force] [--bump=patch|minor|major] [--api-base=URL] [--dry-run]
//
// - Só reconstrói quando o código mudou desde a última publicação (hash do app + mobile-common). `--force` obriga.
// - Versão explícita no pubspec (`version: 1.0.0+3`): o número do build (+N) sobe sozinho a cada mudança, e
//   `--bump` também sobe o nome da versão (patch/minor/major). O Android só aceita atualizar por cima se o build aumentar.
// - O site e os apps leem o manifesto para mostrar a versão e oferecer o download.
import { execFileSync, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const downloads = path.resolve(process.env.DOWNLOADS_DIR || path.join(root, 'downloads'));

const APPS = {
  client: { dir: 'mobile-client', name: 'raykookie', fileBase: 'raykookie-cliente' },
  server: { dir: 'mobile-server', name: 'raykookie gestão', fileBase: 'raykookie-gestao' },
};

const args = process.argv.slice(2);
const flag = (n) => args.includes(`--${n}`);
const opt = (n, d) => (args.find((a) => a.startsWith(`--${n}=`)) ?? '').split('=').slice(1).join('=') || d;
const which = args.find((a) => !a.startsWith('--')) ?? 'all';
const targets = which === 'all' ? Object.keys(APPS) : [which];
if (targets.some((t) => !APPS[t])) { console.error('Uso: node infra/publish-apps.mjs [client|server|all] [--force] [--bump=patch|minor|major] [--api-base=URL] [--dry-run]'); process.exit(2); }
// Endereços públicos: config/urls.json (fonte única); --api-base sobrescreve só a API.
const urls = JSON.parse(fs.readFileSync(path.join(root, 'config', 'urls.json'), 'utf8'));
const apiBase = opt('api-base', urls.API_BASE);
const siteBase = urls.SITE_URL;
const bump = opt('bump', '');
// ID do cliente OAuth "Web" do Google (público; o app o usa como serverClientId). Vem do ambiente ou do GOOGLE_CLIENT_ID do backend/.env.
function readGoogleWebClientId() {
  if (process.env.GOOGLE_SERVER_CLIENT_ID) return process.env.GOOGLE_SERVER_CLIENT_ID;
  try { return /^GOOGLE_CLIENT_ID=(\S+)/m.exec(fs.readFileSync(path.join(root, 'backend', '.env'), 'utf8'))?.[1] ?? ''; } catch { return ''; }
}
const googleWebClientId = readGoogleWebClientId();
if (bump && !['patch', 'minor', 'major'].includes(bump)) { console.error('--bump deve ser patch, minor ou major'); process.exit(2); }

// ---- ferramentas --------------------------------------------------------------------------------------
const env = { ...process.env };
const flutterBin = path.join(os.homedir(), 'development', 'flutter', 'bin');
if (!env.PATH.split(':').some((p) => fs.existsSync(path.join(p, 'flutter')))) env.PATH = `${flutterBin}:${env.PATH}`;
if (!env.JAVA_HOME && fs.existsSync('/usr/lib/jvm/java-21-openjdk-amd64')) env.JAVA_HOME = '/usr/lib/jvm/java-21-openjdk-amd64'; // o JDK 26 quebra o Gradle
env.ANDROID_HOME ??= path.join(os.homedir(), 'Android', 'Sdk');

// ---- versão e hash ------------------------------------------------------------------------------------
const VERSION_RE = /^version:\s*(\d+)\.(\d+)\.(\d+)\+(\d+)\s*$/m;
function readVersion(pubspec) {
  const m = VERSION_RE.exec(fs.readFileSync(pubspec, 'utf8'));
  if (!m) throw new Error(`sem "version: X.Y.Z+N" em ${pubspec}`);
  return { major: +m[1], minor: +m[2], patch: +m[3], build: +m[4] };
}
const versionName = (v) => `${v.major}.${v.minor}.${v.patch}`;
function writeVersion(pubspec, v) {
  fs.writeFileSync(pubspec, fs.readFileSync(pubspec, 'utf8').replace(VERSION_RE, `version: ${versionName(v)}+${v.build}`));
}

const SKIP = new Set(['build', '.dart_tool', '.gradle', 'Pods', '.idea', 'node_modules', '.git']);
const SKIP_FILES = new Set(['local.properties', 'key.properties', '.flutter-plugins', '.flutter-plugins-dependencies', 'GeneratedPluginRegistrant.java']);
function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    if (SKIP.has(e.name) || SKIP_FILES.has(e.name) || e.name.endsWith('.iml')) continue;
    const p = path.join(dir, e.name);
    e.isDirectory() ? walk(p, out) : out.push(p);
  }
  return out;
}
/** Hash do que entra no app (o próprio app + o pacote comum + endereço da API), ignorando só a linha `version:`. */
function sourceHash(appDir) {
  const h = crypto.createHash('sha256');
  h.update(`api:${apiBase}\nsite:${siteBase}\n`);
  for (const base of [appDir, path.join(root, 'mobile-common')]) {
    for (const f of walk(base)) {
      let data = fs.readFileSync(f);
      if (path.basename(f) === 'pubspec.yaml' && base === appDir) data = Buffer.from(data.toString('utf8').replace(VERSION_RE, 'version: X'));
      h.update(`${path.relative(root, f)}\n`).update(data);
    }
  }
  return h.digest('hex');
}

const manifestPath = path.join(downloads, 'manifest.json');
const readManifest = () => { try { return JSON.parse(fs.readFileSync(manifestPath, 'utf8')); } catch { return {}; } };
const sha256File = (f) => crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');

function run(cmd, cmdArgs, cwd) {
  const r = spawnSync(cmd, cmdArgs, { cwd, env, stdio: 'inherit' });
  if (r.status !== 0) throw new Error(`${cmd} ${cmdArgs.join(' ')} falhou (código ${r.status})`);
}

// ---- publicação ---------------------------------------------------------------------------------------
fs.mkdirSync(downloads, { recursive: true });
let failed = false;

for (const app of targets) {
  const cfg = APPS[app];
  const appDir = path.join(root, cfg.dir);
  const pubspec = path.join(appDir, 'pubspec.yaml');
  const manifest = readManifest();
  const current = manifest[app];
  try {
    const hash = sourceHash(appDir);
    const upToDate = current && current.sourceHash === hash && fs.existsSync(path.join(downloads, current.file));
    if (upToDate && !flag('force') && !bump) {
      console.log(`✔ ${app}: já está publicado e atualizado (${current.version}+${current.build}).`);
      continue;
    }

    // decide a nova versão
    const v = readVersion(pubspec);
    if (bump) { v[bump]++; if (bump === 'major') { v.minor = 0; v.patch = 0; } if (bump === 'minor') v.patch = 0; }
    const lastBuild = current?.build ?? 0;
    v.build = v.build > lastBuild && !bump ? v.build : Math.max(v.build, lastBuild) + 1;
    const label = `${versionName(v)}+${v.build}`;
    console.log(`\n▶ ${app}: ${current ? `${current.version}+${current.build} → ` : ''}${label}${flag('dry-run') ? '  (dry-run, nada será feito)' : ''}`);
    if (flag('dry-run')) continue;

    writeVersion(pubspec, v);
    if (!fs.existsSync(path.join(appDir, 'android', 'key.properties'))) console.warn('⚠ android/key.properties ausente: o APK será assinado com a chave de debug (não serve para atualizar apps já instalados).');
    run('flutter', ['pub', 'get'], appDir);
    const defines = [`--dart-define=API_BASE=${apiBase}`, `--dart-define=SITE_BASE=${siteBase}`];
    if (googleWebClientId) defines.push(`--dart-define=GOOGLE_SERVER_CLIENT_ID=${googleWebClientId}`);
    run('flutter', ['build', 'apk', '--release', '--target-platform', 'android-arm64,android-arm', ...defines], appDir);

    const built = path.join(appDir, 'build', 'app', 'outputs', 'flutter-apk', 'app-release.apk');
    if (!fs.existsSync(built)) throw new Error(`APK não encontrado em ${built}`);
    const file = `${cfg.fileBase}-${label}.apk`;
    fs.copyFileSync(built, path.join(downloads, file));

    // confere a assinatura, se o apksigner existir
    const apksigner = path.join(env.ANDROID_HOME, 'build-tools', fs.existsSync(env.ANDROID_HOME) ? (fs.readdirSync(path.join(env.ANDROID_HOME, 'build-tools')).sort().pop() ?? '') : '', 'apksigner');
    if (fs.existsSync(apksigner)) {
      try {
        const out = execFileSync(apksigner, ['verify', '--print-certs', path.join(downloads, file)], { env, encoding: 'utf8' });
        console.log(out.split('\n').filter((l) => /Signer #1 certificate (DN|SHA-256)/.test(l)).join('\n'));
      } catch (e) { throw new Error(`assinatura do APK inválida: ${e.message}`); }
    }

    manifest[app] = {
      app, name: cfg.name, version: versionName(v), build: v.build, file, size: fs.statSync(path.join(downloads, file)).size,
      sha256: sha256File(path.join(downloads, file)), date: new Date().toISOString(), sourceHash: sourceHash(appDir), apiBase,
    };
    fs.writeFileSync(`${manifestPath}.tmp`, JSON.stringify(manifest, null, 2) + '\n');
    fs.renameSync(`${manifestPath}.tmp`, manifestPath);

    // guarda só as 3 versões mais recentes de cada app
    const mine = fs.readdirSync(downloads).filter((f) => f.startsWith(`${cfg.fileBase}-`) && f.endsWith('.apk'))
      .map((f) => ({ f, t: fs.statSync(path.join(downloads, f)).mtimeMs })).sort((a, b) => b.t - a.t);
    for (const old of mine.slice(3)) fs.rmSync(path.join(downloads, old.f));
    console.log(`✔ ${app} publicado: ${file} (${(manifest[app].size / 1048576).toFixed(1)} MB, sha256 ${manifest[app].sha256.slice(0, 12)}…)`);
  } catch (e) {
    failed = true;
    console.error(`✘ ${app}: ${e.message}`);
  }
}
process.exit(failed ? 1 : 0);
