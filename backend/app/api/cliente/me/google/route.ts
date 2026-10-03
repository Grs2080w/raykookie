import { fail, ok } from '@/lib/api';
import { customerRoute } from '@/lib/customer-api';
import { unlinkGoogle } from '@/lib/customer-privacy';

/** Retira o vínculo da conta com o Google (revoga o consentimento do login). */
export const DELETE = customerRoute(async (_req, c) => {
  const r = await unlinkGoogle(c.id);
  return r.error ? fail(r.error, 409) : ok();
});
