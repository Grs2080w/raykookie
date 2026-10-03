import Link from 'next/link';
import { notFound, redirect } from 'next/navigation';
import { apiPublic } from '@/lib/server-api';
import type { PayLinkView } from '@shared/types';
import { RefundForm } from '@/app/cliente/pedidos/[id]/devolucao/RefundForm';

export const metadata = { title: 'Pedir devolução' };

export default async function Page({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  const view = await apiPublic<PayLinkView>(`/api/pagar/${encodeURIComponent(token)}`, 0);
  if (!view) notFound();
  const o = view.shop_order;
  if (!o || o.refund.mode !== 'solicitacao') redirect(`/pagar/${token}`);
  return (
    <>
      <Link href={`/pagar/${token}`} className="inline-flex min-h-11 items-center text-sm font-semibold text-pink-strong">← Voltar ao pedido</Link>
      <h1 className="font-display text-3xl font-semibold leading-tight">Pedir devolução do pedido #{o.code}</h1>
      <p className="mt-1 text-muted">Conte o que aconteceu. A confeiteira analisa e responde aqui mesmo, nesta página.</p>
      <RefundForm order={o} api={`/api/pagar/${encodeURIComponent(token)}/devolucao`} backHref={`/pagar/${token}`} />
    </>
  );
}
