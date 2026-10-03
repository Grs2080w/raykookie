import { PageHeader } from '@/components/PageHeader';
import { requireUser } from '@/lib/auth';
import { getCategories, getFixedCosts, getSettings } from '@/lib/queries';
import { CostSettings } from './CostSettings';

export const metadata = { title: 'Custos e ajustes' };

export default async function Page() {
  const user = await requireUser();
  const [settings, costs, categories] = await Promise.all([getSettings(user.id), getFixedCosts(user.id), getCategories(user.id, 'expense')]);
  return (
    <>
      <PageHeader title="Custos e ajustes" subtitle="O valor da hora e os gastos do mês entram no preço de todas as receitas." />
      <CostSettings settings={settings} costs={costs} categories={categories} />
    </>
  );
}
