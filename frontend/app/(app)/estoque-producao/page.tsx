import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import type { ProductionRow, ProductionRun, ProductionSummary } from '@shared/types';
import { ProductionManager } from './ProductionManager';

export const metadata = { title: 'Estoque de produção' };

export default async function Page() {
  const [stock, runs] = await Promise.all([
    apiGet<{ recipes: ProductionRow[]; summary: ProductionSummary }>('/api/production/stock'),
    apiGet<ProductionRun[]>('/api/production/runs'),
  ]);
  return (
    <>
      <PageHeader title="Estoque de produção" subtitle="Unidades prontas de cada receita. Ao registrar uma produção, os ingredientes saem do estoque sozinhos." />
      <ProductionManager recipes={stock.recipes} summary={stock.summary} runs={runs} />
    </>
  );
}
