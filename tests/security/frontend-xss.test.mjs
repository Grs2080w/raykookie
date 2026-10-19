// Segurança do site e dos apps: nenhum caminho de HTML/JS dinâmico com dado do usuário. Rodar: node --test tests/security/
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function walk(dir, exts, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (['node_modules', '.next', 'build', '.dart_tool'].includes(entry.name)) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, exts, out);
    else if (exts.some((e) => entry.name.endsWith(e))) out.push(full);
  }
  return out;
}

const siteFiles = ['app', 'components', 'lib'].flatMap((d) => walk(path.join(root, 'frontend', d), ['.ts', '.tsx', '.js', '.jsx']));

test('site: sem innerHTML, outerHTML, document.write, eval nem new Function', () => {
  const sinks = /\.innerHTML\b|\.outerHTML\b|document\.write\(|\beval\(|new Function\(|insertAdjacentHTML/;
  const found = siteFiles.filter((f) => sinks.test(fs.readFileSync(f, 'utf8'))).map((f) => path.relative(root, f));
  assert.deepEqual(found, []);
});

test('site: dangerouslySetInnerHTML só no layout, com uma constante fixa (nunca dado do usuário)', () => {
  const users = siteFiles.filter((f) => fs.readFileSync(f, 'utf8').includes('dangerouslySetInnerHTML'));
  assert.deepEqual(users.map((f) => path.relative(root, f)), ['frontend/app/layout.tsx']);
  const src = fs.readFileSync(users[0], 'utf8');
  assert.match(src, /dangerouslySetInnerHTML=\{\{ __html: themeScript \}\}/);
  assert.match(src, /const themeScript = `[^$`]*`;/, 'themeScript é um texto fixo, sem ${...}');
});

test('apps: sem renderizador de HTML; WebView só no app do cliente e só nas duas telas do Mercado Pago', () => {
  for (const app of ['mobile-server', 'mobile-client', 'mobile-common']) {
    const pubspec = fs.readFileSync(path.join(root, app, 'pubspec.yaml'), 'utf8');
    for (const pkg of ['flutter_html', 'flutter_widget_from_html', 'flutter_inappwebview', 'flutter_markdown']) {
      assert.ok(!pubspec.includes(pkg), `${app} usa ${pkg}`);
    }
    if (app !== 'mobile-client') assert.ok(!pubspec.includes('webview_flutter'), `${app} usa webview_flutter`);
  }
  // WebView existe só para o 3DS e para o identificador do aparelho do Mercado Pago (conteúdo fixo ou do próprio banco)
  const users = walk(path.join(root, 'mobile-client', 'lib'), ['.dart'])
    .filter((f) => fs.readFileSync(f, 'utf8').includes('WebViewController'))
    .map((f) => path.relative(path.join(root, 'mobile-client', 'lib'), f))
    .sort();
  assert.deepEqual(users, ['features/customer/mp_device.dart', 'features/customer/three_ds_challenge.dart']);
});

test('site: links montados com dado do usuário passam por next/link ou caminho fixo (sem javascript:)', () => {
  const bad = siteFiles.filter((f) => /href=\{`?javascript:/i.test(fs.readFileSync(f, 'utf8'))).map((f) => path.relative(root, f));
  assert.deepEqual(bad, []);
});
