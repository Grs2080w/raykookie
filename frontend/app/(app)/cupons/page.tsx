import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import type { Coupon } from '@shared/types';
import { CouponsManager } from './CouponsManager';

export const metadata = { title: 'Cupons' };

export default async function Page() {
  const coupons = await apiGet<Coupon[]>('/api/coupons');
  return (
    <>
      <PageHeader title="Cupons" subtitle="O cliente digita o código no último passo do pedido. O desconto é calculado sobre o total dos itens." />
      <CouponsManager initial={coupons} />
    </>
  );
}
