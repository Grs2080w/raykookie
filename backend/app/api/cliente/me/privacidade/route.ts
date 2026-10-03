import { fail, ok } from '@/lib/api';
import { customerRoute } from '@/lib/customer-api';
import { createPrivacyRequest } from '@/lib/customer-privacy';
import { notifyPrivacyRequest } from '@/lib/push';
import { shopOwnerId } from '@/lib/shop-queries';

/** Solicitação sobre os dados (anonimização, bloqueio, eliminação, revogação...). A loja responde em até 15 dias. */
export const POST = customerRoute(async (req, c) => {
  const b = await req.json().catch(() => ({}));
  const r = await createPrivacyRequest(c.id, b.type, b.details);
  if (r.error) return fail(r.error);
  const owner = await shopOwnerId();
  if (owner) void notifyPrivacyRequest(owner, { id: r.id!, type: String(b.type) });
  return ok({ ok: true, id: r.id }, 201);
});
