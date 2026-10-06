import { fail, ok } from '@/lib/api';
import { customerRoute } from '@/lib/customer-api';
import { readFeedbackForm } from '@/lib/feedback-form';
import { submitFeedback } from '@/lib/feedback';
import { shopOwnerId } from '@/lib/shop-queries';

// O cliente manda um feedback (nota 1-5, mensagem e até 5 fotos, multipart/form-data). Precisa estar logado.
export const POST = customerRoute(async (req, customer) => {
  const f = await readFeedbackForm(req);
  if (!f.ok) return fail(f.error, f.status);
  const owner = await shopOwnerId();
  if (!owner) return fail('A loja está indisponível no momento.', 503);
  const r = await submitFeedback(owner, customer.id, f.input);
  return r.ok ? ok({ ok: true, id: r.id }, 201) : fail(r.error, r.status);
});
