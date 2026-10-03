import { customerRoute } from '@/lib/customer-api';
import { exportData } from '@/lib/customer-privacy';

/** Portabilidade: cópia dos dados do próprio cliente em JSON. */
export const GET = customerRoute(async (_req, c) => {
  const body = JSON.stringify(await exportData(c.id), null, 2);
  return new Response(body, {
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Disposition': 'attachment; filename="meus-dados-raykookie.json"',
      'Cache-Control': 'no-store',
    },
  });
});
