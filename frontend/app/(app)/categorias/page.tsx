import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import type { Category } from '@shared/types';
import { CategoriesManager } from './CategoriesManager';

export const metadata = { title: 'Categorias' };

export default async function Page() {
  const categories = await apiGet<Category[]>('/api/categories');
  return (
    <>
      <PageHeader title="Categorias" subtitle="Organize ingredientes, receitas e gastos. Crie as suas, escolha um ícone e uma cor." />
      <CategoriesManager categories={categories} />
    </>
  );
}
