import { notFound } from 'next/navigation';
import { apiGet } from '@/lib/server-api';
import type { Category, FixedCost, Ingredient, RecipeRow, Settings } from '@shared/types';
import { RecipeEditor } from './RecipeEditor';

export const metadata = { title: 'Receita' };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const isNew = id === 'nova';
  if (!isNew && !/^\d+$/.test(id)) notFound();
  const [ingredients, categories, settings, costs, recipe] = await Promise.all([
    apiGet<Ingredient[]>('/api/ingredients'), apiGet<Category[]>('/api/categories?kind=recipe'), apiGet<Settings>('/api/settings'),
    apiGet<FixedCost[]>('/api/fixed-costs'), isNew ? Promise.resolve(null) : apiGet<RecipeRow>(`/api/recipes/${id}`),
  ]);
  const fixedTotal = costs.reduce((s, c) => s + c.amount, 0);
  return <RecipeEditor recipe={recipe} ingredients={ingredients} categories={categories} settings={settings} fixedTotal={fixedTotal} />;
}
