import { requireCustomer } from '@/lib/customer-auth';
import { getMenu } from '@/lib/shop-menu';
import { addDays } from '@/lib/text';
import { LEAD_DAYS } from '@/lib/shop';
import { NewOrderForm } from './NewOrderForm';

export const metadata = { title: 'Novo pedido' };

export default async function Page({ searchParams }: { searchParams: Promise<{ item?: string }> }) {
  const { item } = await searchParams;
  const customer = await requireCustomer(item ? `/cliente/pedidos/novo?item=${encodeURIComponent(item)}` : '/cliente/pedidos/novo');
  const today = new Date().toLocaleDateString('sv-SE', { timeZone: 'America/Sao_Paulo' });
  const menu = await getMenu();
  return <NewOrderForm customer={customer} menu={menu} initialItem={item} minDate={addDays(today, LEAD_DAYS)} />;
}
