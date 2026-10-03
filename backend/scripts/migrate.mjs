// Aplica migrations/*.sql em ordem, uma transação por arquivo. Idempotente.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
for (const line of (fs.existsSync(path.join(root, '.env')) ? fs.readFileSync(path.join(root, '.env'), 'utf8') : '').split('\n')) {
  const m = line.match(/^\s*([A-Z_]+)\s*=\s*(.*)\s*$/);
  if (m && !(m[1] in process.env)) process.env[m[1]] = m[2].replace(/^"|"$/g, '');
}
if (!process.env.DATABASE_URL) { console.error('DATABASE_URL não definida (.env)'); process.exit(1); }

const client = new pg.Client({ connectionString: process.env.DATABASE_URL });
await client.connect();
await client.query(`CREATE TABLE IF NOT EXISTS _migrations (name TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())`);
const done = new Set((await client.query('SELECT name FROM _migrations')).rows.map((r) => r.name));
const dir = path.join(root, 'migrations');
for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.sql')).sort()) {
  if (done.has(f)) { console.log('já aplicada:', f); continue; }
  try {
    await client.query('BEGIN');
    await client.query(fs.readFileSync(path.join(dir, f), 'utf8'));
    await client.query('INSERT INTO _migrations(name) VALUES ($1)', [f]);
    await client.query('COMMIT');
    console.log('aplicada:', f);
  } catch (e) { await client.query('ROLLBACK'); console.error('falhou:', f, e.message); process.exit(1); }
}
await client.end();
