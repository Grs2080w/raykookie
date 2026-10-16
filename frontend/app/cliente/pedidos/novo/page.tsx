import { todayBR } from '@/lib/dates';
import { apiPublic, requireCustomer } from '@/lib/server-api';
import type { Menu } from '@shared/types';
import { addDays } from '@shared/text';
import { LEAD_DAYS, MAX_QTY } from '@shared/shop';
import { NewOrderForm } from './NewOrderForm';

export const metadata = { title: 'Novo pedido', description: 'Monte sua encomenda de cookies, tortas, brownies e bolos feitos à mão.' };

/** `itens=id:qtd,id:qtd` (a caixa montada na landing) → quantidades por id; o formulário descarta ids que não estão no cardápio. */
function parseLines(raw?: string): Record<string, number> | undefined {
  if (!raw) return undefined;
  const out: Record<string, number> = {};
  for (const part of raw.split(',').slice(0, 30)) {
    const [id, n] = part.split(':');
    const qty = Math.min(MAX_QTY, Math.max(0, Math.trunc(Number(n))));
    if (id && qty > 0) out[id] = qty;
  }
  return Object.keys(out).length ? out : undefined;
}

export default async function Page({ searchParams }: { searchParams: Promise<{ item?: string; itens?: string; from?: string }> }) {
  const { item, itens, from } = await searchParams;
  // o estado fica na URL: o item escolhido e de onde a pessoa veio. Sem sessão, o login devolve para exatamente esta URL.
  const origin = from === 'cardapio' || from === 'home' ? from : undefined;
  const qs = new URLSearchParams();
  if (item) qs.set('item', item);
  if (itens) qs.set('itens', itens);
  if (origin) qs.set('from', origin);
  const customer = await requireCustomer(`/cliente/pedidos/novo${qs.size ? `?${qs}` : ''}`);
  const today = todayBR();
  const menu = (await apiPublic<Menu>('/api/menu', 10)) ?? { items: [], groups: [] };
  const pickup = (await apiPublic<{ label: string; address: string; instructions: string }>('/api/pickup', 10)) ?? { label: '', address: '', instructions: '' };
  return <NewOrderForm customer={customer} menu={menu} pickup={pickup} initialItem={item} initialLines={parseLines(itens)} backHref={origin === 'cardapio' ? '/cardapio' : origin === 'home' ? '/#cardapio' : undefined} minDate={addDays(today, LEAD_DAYS)} />;
}
