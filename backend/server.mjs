// Servidor único de produção: UMA porta para tudo.
//   /api/*  → API (este projeto, backend/)
//   o resto → site, loja e painel, só se FRONTEND_DIR apontar para um frontend buildado
// Cada projeto roda com o próprio Next e o próprio build (.next), só que dentro do mesmo processo.
// Sem FRONTEND_DIR o backend não procura nada fora da própria pasta e responde só a API.
import http from 'node:http';
import path from 'node:path';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import next from 'next';

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = process.env.FRONTEND_DIR ? path.resolve(here, process.env.FRONTEND_DIR) : null;
const port = Number(process.env.PORT || 10005);
const host = process.env.HOST || '0.0.0.0';

const distDir = process.env.NEXT_DIST_DIR || '.next';
if (!fs.existsSync(path.join(here, distDir))) { console.error('Falta o build do backend. Rode: npm run build (em backend/)'); process.exit(1); }
const hasFront = !!frontendDir && fs.existsSync(path.join(frontendDir, distDir));
if (frontendDir && !hasFront) console.warn(`[rayka] sem build do frontend em ${frontendDir}: só a API vai responder (rode npm run build em frontend/)`);

const api = next({ dev: false, dir: here });
// o frontend usa o Next instalado na própria pasta (as dependências dele ficam lá). Só tenta o require
// quando a pasta existe: sem ela (imagem Docker só com a API), createRequire falharia ao resolver 'next'.
const site = hasFront ? createRequire(path.join(frontendDir, 'package.json'))('next')({ dev: false, dir: frontendDir }) : null;

await Promise.all([api.prepare(), site?.prepare()]);
const handleApi = api.getRequestHandler();
const handleSite = site?.getRequestHandler();

const server = http.createServer((req, res) => {
  const p = req.url ?? '/';
  if (p === '/api' || p.startsWith('/api/') || p.startsWith('/api?') || !handleSite) return handleApi(req, res);
  return handleSite(req, res);
});
server.listen(port, host, () => console.log(`[rayka] no ar em http://${host}:${port}  (API + ${hasFront ? 'site' : 'sem site'})`));

// Tarefas periódicas (refazer devoluções que falharam, conferir pagamentos com o Mercado Pago): um "tick" a cada 5 minutos,
// chamado por este mesmo processo na própria API. Só roda se JOBS_SECRET estiver no .env.
if (process.env.JOBS_SECRET) {
  const tick = () => fetch(`http://127.0.0.1:${port}/api/jobs/tick`, { method: 'POST', headers: { 'x-jobs-secret': process.env.JOBS_SECRET }, signal: AbortSignal.timeout(120_000) })
    .catch((e) => console.error('[rayka] tarefas periódicas falharam:', e.message));
  setTimeout(tick, 30_000);
  setInterval(tick, 5 * 60_000).unref();
}

for (const sig of ['SIGTERM', 'SIGINT']) process.on(sig, () => server.close(() => process.exit(0)));
