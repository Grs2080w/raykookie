import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import { RefundsManager, type RefundList } from './RefundsManager';

export const metadata = { title: 'Devoluções' };

export default async function Page({ searchParams }: { searchParams: Promise<{ status?: string }> }) {
  const { status = 'aguardando' } = await searchParams;
  const data = await apiGet<RefundList>(`/api/devolucoes?status=${encodeURIComponent(status)}`);
  return (
    <>
      <PageHeader title="Devoluções" subtitle="Pedidos de devolução dos clientes e devoluções de Pix em andamento. Fora do cancelamento antes da produção, a decisão é sua, caso a caso." />
      <RefundsManager data={data} status={status} />
    </>
  );
}
