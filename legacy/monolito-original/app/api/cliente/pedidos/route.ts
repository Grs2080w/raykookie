import { fail, ok } from '@/lib/api';
import { customerRoute } from '@/lib/customer-api';
import { getMenu } from '@/lib/shop-menu';
import { createShopOrder, shopOwnerId } from '@/lib/shop-queries';
import { parseShopOrder } from '@/lib/shop';

// data de hoje no fuso da loja (o servidor pode estar em UTC)
const today = () => new Date().toLocaleDateString('sv-SE', { timeZone: 'America/Sao_Paulo' });

export const POST = customerRoute(async (req, customer) => {
  const b = await req.json().catch(() => null);
  const menu = await getMenu(); // preços sempre lidos das receitas no servidor
  const p = parseShopOrder(b, today(), menu.items);
  if (p.error) return fail(p.error);
  const owner = await shopOwnerId();
  if (!owner) return fail('A loja não está recebendo pedidos no momento. Tente mais tarde.', 503);
  const id = await createShopOrder(customer, owner, p.value!);
  return ok({ id }, 201);
});
