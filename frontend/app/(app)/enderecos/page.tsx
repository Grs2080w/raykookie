import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import { PickupManager, type Pickup } from './PickupManager';

export const metadata = { title: 'Endereço de retirada' };

export default async function Page() {
  const list = await apiGet<Pickup[]>('/api/pickup-addresses');
  return (
    <>
      <PageHeader title="Endereço de retirada" subtitle="Escolha onde os clientes retiram os pedidos. O endereço atual aparece para o cliente ao pedir e no pedido dele. Guarde os que você mais usa e troque quando precisar." />
      <PickupManager list={list} />
    </>
  );
}
