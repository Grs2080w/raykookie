import { EmptyState, PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import { FeedbackList, type FeedbackRow } from './FeedbackList';
import { MessageSquareHeart } from 'lucide-react';

export const metadata = { title: 'Feedback' };

export default async function Page() {
  const { list } = await apiGet<{ list: FeedbackRow[] }>('/api/feedback');
  return (
    <>
      <PageHeader title="Feedback" subtitle="Nota, mensagem e fotos que os clientes deixam depois de receber o pedido." />
      {list.length === 0
        ? <EmptyState icon={<MessageSquareHeart size={26} />} title="Nenhum feedback ainda" text="Quando um cliente avaliar um pedido entregue, aparece aqui." />
        : <FeedbackList list={list} />}
    </>
  );
}
