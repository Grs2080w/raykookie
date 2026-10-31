import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import type { Category, Ingredient, ProductionRow, ProductionSummary } from '@shared/types';
import { StockManager } from './StockManager';

export const metadata = { title: 'Estoque de ingredientes' };

export default async function Page() {
  const [ingredients, categories, production] = await Promise.all([
    apiGet<Ingredient[]>('/api/ingredients'),
    apiGet<Category[]>('/api/categories?kind=ingredient'),
    // o painel "Dá para produzir" é um extra: se o endpoint falhar, o estoque de ingredientes abre do mesmo jeito
    apiGet<{ recipes: ProductionRow[]; summary: ProductionSummary }>('/api/production/stock').catch(() => ({ recipes: [] as ProductionRow[], summary: { units: 0, cost: 0, revenue: 0, profit: 0 } })),
  ]);
  return (
    <>
      <PageHeader title="Estoque de ingredientes" subtitle="O custo por grama, mililitro ou unidade é calculado sozinho a partir do preço da embalagem." />
      <StockManager ingredients={ingredients} categories={categories} producible={production.recipes} />
    </>
  );
}
