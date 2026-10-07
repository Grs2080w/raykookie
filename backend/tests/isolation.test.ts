// O backend precisa funcionar sozinho: nenhum import, alias ou config pode apontar para fora desta pasta.
import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const root = path.resolve(__dirname, '..');
const SKIP = new Set(['node_modules', 'downloads', 'tests']);
const inside = (p: string) => p === root || p.startsWith(root + path.sep);

function sources(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    if (e.name.startsWith('.') || SKIP.has(e.name)) return [];
    const full = path.join(dir, e.name);
    if (e.isDirectory()) return sources(full);
    return /\.(ts|tsx|mjs|js)$/.test(e.name) ? [full] : [];
  });
}

const relativeSpecifiers = (code: string) =>
  [...code.matchAll(/(?:from\s*|import\(\s*|require\(\s*|new URL\(\s*)['"`](\.{1,2}\/[^'"`]*)['"`]/g)].map((m) => m[1]);

describe('backend isolado', () => {
  it('nenhum import relativo sai da pasta backend/', () => {
    const files = [...sources(root), ...fs.readdirSync(path.join(root, 'tests')).filter((f) => f.endsWith('.ts')).map((f) => path.join(root, 'tests', f))];
    const escapes = files.flatMap((f) =>
      relativeSpecifiers(fs.readFileSync(f, 'utf8'))
        .filter((spec) => !inside(path.resolve(path.dirname(f), spec)))
        .map((spec) => `${path.relative(root, f)} → ${spec}`));
    expect(escapes).toEqual([]);
  });

  it('aliases do tsconfig e do vitest resolvem dentro do backend', async () => {
    const ts = JSON.parse(fs.readFileSync(path.join(root, 'tsconfig.json'), 'utf8'));
    for (const targets of Object.values<string[]>(ts.compilerOptions.paths)) {
      for (const t of targets) expect(inside(path.resolve(root, t.replace('*', '')))).toBe(true);
    }
    for (const inc of ts.include as string[]) expect(inc.startsWith('..')).toBe(false);
    const vitest = fs.readFileSync(path.join(root, 'vitest.config.ts'), 'utf8');
    expect(vitest).not.toMatch(/\.\.\//);
  });

  it('next.config não usa a raiz do repositório nem diretórios externos', () => {
    const cfg = fs.readFileSync(path.join(root, 'next.config.mjs'), 'utf8');
    expect(cfg).not.toMatch(/new URL\(['"]\.\.['"]/);
    expect(cfg).not.toMatch(/externalDir/);
  });

  it('server.mjs só usa um frontend se FRONTEND_DIR for informado', () => {
    const server = fs.readFileSync(path.join(root, 'server.mjs'), 'utf8');
    expect(server).not.toMatch(/['"]\.\.\/frontend['"]/);
  });

  it('todo módulo importado via @shared existe em backend/shared', () => {
    const missing = sources(root).flatMap((f) =>
      [...fs.readFileSync(f, 'utf8').matchAll(/['"]@shared\/([\w-]+)['"]/g)]
        .map((m) => m[1])
        .filter((mod) => !fs.existsSync(path.join(root, 'shared', `${mod}.ts`))));
    expect(missing).toEqual([]);
  });
});
