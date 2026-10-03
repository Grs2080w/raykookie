import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import { PrivacyRequests, type Req } from './PrivacyRequests';

export const metadata = { title: 'Privacidade dos clientes' };

export default async function Page() {
  const list = await apiGet<Req[]>('/api/privacy-requests');
  return (
    <>
      <PageHeader title="Privacidade dos clientes" subtitle="Solicitações que os clientes fazem sobre os próprios dados (LGPD). A resposta tem prazo de 15 dias." />
      <PrivacyRequests list={list} />
    </>
  );
}
