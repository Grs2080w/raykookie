import { PageHeader } from '@/components/PageHeader';
import { requireUser } from '@/lib/auth';
import { getCategories, getIngredients } from '@/lib/queries';
import { StockManager } from './StockManager';

export const metadata = { title: 'Estoque' };

export default async function Page() {
  const user = await requireUser();
  const [ingredients, categories] = await Promise.all([getIngredients(user.id), getCategories(user.id, 'ingredient')]);
  return (
    <>
      <PageHeader title="Estoque de ingredientes" subtitle="O custo por grama, mililitro ou unidade é calculado sozinho a partir do preço da embalagem." />
      <StockManager ingredients={ingredients} categories={categories} />
    </>
  );
}
