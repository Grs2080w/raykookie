import { notFound } from 'next/navigation';
import { requireUser } from '@/lib/auth';
import { getCategories, getFixedTotal, getIngredients, getRecipes, getSettings } from '@/lib/queries';
import { RecipeEditor } from './RecipeEditor';

export const metadata = { title: 'Receita' };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const user = await requireUser();
  const isNew = id === 'nova';
  if (!isNew && !/^\d+$/.test(id)) notFound();
  const [ingredients, categories, settings, fixedTotal, recipes] = await Promise.all([
    getIngredients(user.id), getCategories(user.id, 'recipe'), getSettings(user.id), getFixedTotal(user.id), isNew ? Promise.resolve([]) : getRecipes(user.id),
  ]);
  const recipe = isNew ? null : recipes.find((r) => r.id === Number(id));
  if (!isNew && !recipe) notFound();
  return <RecipeEditor recipe={recipe ?? null} ingredients={ingredients} categories={categories} settings={settings} fixedTotal={fixedTotal} />;
}
