import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import type { Category, Ingredient } from '@shared/types';
import { StockManager } from './StockManager';

export const metadata = { title: 'Estoque' };

export default async function Page() {
  const [ingredients, categories] = await Promise.all([apiGet<Ingredient[]>('/api/ingredients'), apiGet<Category[]>('/api/categories?kind=ingredient')]);
  return (
    <>
      <PageHeader title="Estoque de ingredientes" subtitle="O custo por grama, mililitro ou unidade é calculado sozinho a partir do preço da embalagem." />
      <StockManager ingredients={ingredients} categories={categories} />
    </>
  );
}
