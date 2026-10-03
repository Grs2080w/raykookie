import { PageHeader } from '@/components/PageHeader';
import { apiGet } from '@/lib/server-api';
import { SuppliersManager, type Supplier } from './SuppliersManager';

export const metadata = { title: 'Fornecedores' };

export default async function Page() {
  const list = await apiGet<Supplier[]>('/api/suppliers');
  return (
    <>
      <PageHeader title="Fornecedores" subtitle="Cadastre os sites e as lojas onde você compra. Nos sites com busca, o app procura os preços por você; nas lojas físicas, você guarda as cotações à mão." />
      <SuppliersManager list={list} />
    </>
  );
}
