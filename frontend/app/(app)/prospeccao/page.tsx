import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import type { Category, PricedRecipe } from '@shared/types';
import { ProspectionTool, type CatalogProduct } from './ProspectionTool';

export const metadata = { title: 'Prospecção' };

export default async function Page() {
  const [recipes, cats] = await Promise.all([apiGet<PricedRecipe[]>('/api/recipes'), apiGet<Category[]>('/api/categories?kind=recipe')]);
  const catName = new Map(cats.map((c) => [c.id, c.name]));
  // Só o que a simulação usa: custo por unidade e preço de venda (o do cardápio, quando fixado). Nada daqui volta para o cadastro.
  const products: CatalogProduct[] = recipes.map((r) => ({
    id: r.id, name: r.name, category: r.category_id ? (catName.get(r.category_id) ?? null) : null,
    cost: r.pricing.perUnit, price: r.pricing.rounded,
  }));
  return (
    <>
      <PageHeader title="Prospecção" subtitle="Escolha os produtos, defina a quantidade e veja o preço, o desconto, o lucro e a margem na hora. Depois gere a tabela para print ou exporte em CSV." />
      <ProspectionTool products={products} categories={[...new Set(products.map((p) => p.category).filter((c): c is string => !!c))]} />
    </>
  );
}
