import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import type { Order, PricedRecipe } from '@shared/types';
import { OrdersManager } from './OrdersManager';

export const metadata = { title: 'Pedidos' };

export default async function Page() {
  const [orders, recipes] = await Promise.all([apiGet<Order[]>('/api/orders'), apiGet<PricedRecipe[]>('/api/recipes')]);
  return (
    <>
      <PageHeader title="Pedidos" subtitle="Acompanhe cada encomenda, da entrada até a entrega e o pagamento." />
      <OrdersManager orders={orders} recipes={recipes.map((r) => ({ id: r.id, name: r.name, price: r.pricing.rounded }))} />
    </>
  );
}
