import { q1 } from '@/lib/db';

// Verificação de saúde (usada por deploy e pipeline): responde 200 se o backend e o banco estão de pé.
export async function GET() {
  try { await q1('SELECT 1'); return Response.json({ ok: true }); }
  catch { return Response.json({ ok: false }, { status: 503 }); }
}
