import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import { AppDownload } from '@/components/AppDownload';
import type { AppReleases, Category, FixedCost, Settings } from '@shared/types';
import { CostSettings } from './CostSettings';

export const metadata = { title: 'Custos e ajustes' };

export default async function Page() {
  const [settings, costs, categories, releases] = await Promise.all([
    apiGet<Settings>('/api/settings'), apiGet<FixedCost[]>('/api/fixed-costs'), apiGet<Category[]>('/api/categories?kind=expense'), apiGet<AppReleases>('/api/downloads'),
  ]);
  return (
    <>
      <PageHeader title="Custos e ajustes" subtitle="O valor da hora e os gastos do mês entram no preço de todas as receitas." />
      <CostSettings settings={settings} costs={costs} categories={categories} />
      <div className="mt-8 max-w-2xl">
        <AppDownload
          id="app-gestao" release={releases.server} icon="/app/icone-gestao.png" title="App de gestão (Android)" dark
          description="Estoque, receitas, pedidos, metas e custos no celular. Entre com a mesma conta do painel."
        />
      </div>
    </>
  );
}
