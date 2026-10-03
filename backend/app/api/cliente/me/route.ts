import { fail, ok } from '@/lib/api';
import { getCustomer } from '@/lib/customer-auth';
import { customerRoute } from '@/lib/customer-api';
import { deleteAccount, updateProfile } from '@/lib/customer-privacy';
import { destroyCustomerSession } from '@/lib/customer-auth';

export async function GET() {
  const c = await getCustomer();
  return c ? ok(c) : fail('Entre para continuar.', 401);
}

/** Correção dos dados: nome, telefone e endereço. */
export const PATCH = customerRoute(async (req, c) => {
  const r = await updateProfile(c.id, await req.json().catch(() => ({})));
  return r.error ? fail(r.error) : ok();
});

/** Exclusão da conta (anonimização). Body: { confirm: "EXCLUIR", password? }. */
export const DELETE = customerRoute(async (req, c) => {
  const b = await req.json().catch(() => ({}));
  const r = await deleteAccount(c.id, b.confirm, b.password);
  if (!r.ok) return fail(r.error, r.status);
  await destroyCustomerSession();
  return ok();
});
