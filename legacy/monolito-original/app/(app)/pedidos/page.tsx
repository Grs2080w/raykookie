import { PageHeader } from '@/components/PageHeader';
import { requireUser } from '@/lib/auth';
import { getOrders, getPricedRecipes } from '@/lib/queries';
import { OrdersManager } from './OrdersManager';

export const metadata = { title: 'Pedidos' };

export default async function Page() {
  const user = await requireUser();
  const [orders, recipes] = await Promise.all([getOrders(user.id), getPricedRecipes(user.id)]);
  return (
    <>
      <PageHeader title="Pedidos" subtitle="Acompanhe cada encomenda, da entrada até a entrega e o pagamento." />
      <OrdersManager orders={orders} recipes={recipes.map((r) => ({ id: r.id, name: r.name, price: r.pricing.rounded }))} />
    </>
  );
}
