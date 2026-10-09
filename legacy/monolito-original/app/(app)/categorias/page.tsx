import { PageHeader } from '@/components/PageHeader';
import { requireUser } from '@/lib/auth';
import { getCategories } from '@/lib/queries';
import { CategoriesManager } from './CategoriesManager';

export const metadata = { title: 'Categorias' };

export default async function Page() {
  const user = await requireUser();
  const categories = await getCategories(user.id);
  return (
    <>
      <PageHeader title="Categorias" subtitle="Organize ingredientes, receitas e gastos. Crie as suas, escolha um ícone e uma cor." />
      <CategoriesManager categories={categories} />
    </>
  );
}
