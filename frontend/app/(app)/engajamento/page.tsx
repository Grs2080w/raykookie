import { PageHeader } from '@/components/PageHeader';
import { EngagementView } from './EngagementView';

export const metadata = { title: 'Engajamento' };

export default function Page() {
  return (
    <>
      <PageHeader title="Engajamento" subtitle="O que os clientes veem e fazem no site e no app: receitas que mais chamam atenção, páginas visitadas e até onde chegam no pedido. A medição é anônima: não identifica ninguém." />
      <EngagementView />
    </>
  );
}
