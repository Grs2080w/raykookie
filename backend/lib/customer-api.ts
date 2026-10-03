import { fail } from './api';
import { Customer, getCustomer } from './customer-auth';

type Ctx = { params: Promise<Record<string, string>> };
type Handler = (req: Request, customer: Customer, params: Record<string, string>) => Promise<Response>;

/** Como `route` do painel, mas exige sessão de cliente. */
export function customerRoute(h: Handler) {
  return async (req: Request, ctx: Ctx) => {
    const customer = await getCustomer();
    if (!customer) return fail('Sua sessão expirou. Entre de novo para continuar.', 401);
    try { return await h(req, customer, (await ctx?.params) ?? {}); }
    catch (e: any) {
      console.error(e);
      return fail('Algo deu errado por aqui. Tente de novo em instantes.', 500);
    }
  };
}
