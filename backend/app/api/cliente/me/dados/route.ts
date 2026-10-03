import { fail, ok } from '@/lib/api';
import { customerRoute } from '@/lib/customer-api';
import { getMyData, myRequests } from '@/lib/customer-privacy';
import { PRIVACY_DEADLINE_DAYS, PRIVACY_TYPES } from '@shared/lgpd';

/** "Meus dados": o que guardamos, solicitações feitas e os tipos de solicitação aceitos. */
export const GET = customerRoute(async (_req, c) => {
  const data = await getMyData(c.id);
  if (!data) return fail('Conta não encontrada.', 404);
  return ok({ ...data, requests: await myRequests(c.id), request_types: PRIVACY_TYPES, deadline_days: PRIVACY_DEADLINE_DAYS });
});
