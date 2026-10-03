import { notFound } from 'next/navigation';
import { apiPublic } from '@/lib/server-api';
import type { PayLinkView } from '@shared/types';
import { OrderCard, orderLinks } from '@/components/OrderCard';
import { PayLink } from './PayLink';

export default async function Page({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  const view = await apiPublic<PayLinkView>(`/api/pagar/${encodeURIComponent(token)}`, 0); // 0 = sem cache
  if (!view) notFound();
  return (
    <PayLink token={token} view={view}>
      {view.shop_order && <OrderCard o={view.shop_order} links={orderLinks(view.shop_order, token)} />}
    </PayLink>
  );
}
