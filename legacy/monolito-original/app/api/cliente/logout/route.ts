import { ok } from '@/lib/api';
import { destroyCustomerSession } from '@/lib/customer-auth';

export async function POST() {
  await destroyCustomerSession();
  return ok();
}
