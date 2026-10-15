import Link from 'next/link';
import { redirect } from 'next/navigation';
import { apiGet, requireCustomer } from '@/lib/server-api';
import type { ShopOrder } from '@shared/types';
import { RefundForm } from './RefundForm';

export const metadata = { title: 'Pedir devolução', description: 'Solicite a devolução de um pedido da Raykookie.' };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  await requireCustomer(`/cliente/pedidos/${id}/devolucao`);
  const orders = await apiGet<ShopOrder[]>('/api/cliente/pedidos', '/cliente/entrar');
  const o = orders.find((x) => String(x.id) === id);
  if (!o || o.refund.mode !== 'solicitacao') redirect('/cliente/pedidos');
  return (
    <>
      <Link href="/cliente/pedidos" className="inline-flex min-h-11 items-center text-sm font-semibold text-pink-strong">← Voltar aos pedidos</Link>
      <h1 className="font-display text-3xl font-semibold leading-tight">Pedir devolução do pedido #{o.code}</h1>
      <p className="mt-1 text-muted">Conte o que aconteceu. A confeiteira analisa e responde aqui mesmo, na tela do pedido.</p>
      <RefundForm order={o} />
    </>
  );
}
