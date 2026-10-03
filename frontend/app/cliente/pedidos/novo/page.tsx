import { todayBR } from '@/lib/dates';
import { apiPublic, requireCustomer } from '@/lib/server-api';
import type { Menu } from '@shared/types';
import { addDays } from '@shared/text';
import { LEAD_DAYS } from '@shared/shop';
import { NewOrderForm } from './NewOrderForm';

export const metadata = { title: 'Novo pedido' };

export default async function Page({ searchParams }: { searchParams: Promise<{ item?: string; from?: string }> }) {
  const { item, from } = await searchParams;
  // o estado fica na URL: o item escolhido e de onde a pessoa veio. Sem sessão, o login devolve para exatamente esta URL.
  const origin = from === 'cardapio' || from === 'home' ? from : undefined;
  const qs = new URLSearchParams();
  if (item) qs.set('item', item);
  if (origin) qs.set('from', origin);
  const customer = await requireCustomer(`/cliente/pedidos/novo${qs.size ? `?${qs}` : ''}`);
  const today = todayBR();
  const menu = (await apiPublic<Menu>('/api/menu', 10)) ?? { items: [], groups: [] };
  const pickup = (await apiPublic<{ label: string; address: string; instructions: string }>('/api/pickup', 10)) ?? { label: '', address: '', instructions: '' };
  return <NewOrderForm customer={customer} menu={menu} pickup={pickup} initialItem={item} backHref={origin === 'cardapio' ? '/cardapio' : origin === 'home' ? '/#cardapio' : undefined} minDate={addDays(today, LEAD_DAYS)} />;
}
