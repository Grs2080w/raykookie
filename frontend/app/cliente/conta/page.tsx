import { requireCustomer } from '@/lib/server-api';
import { apiGet } from '@/lib/server-api';
import { MyData, type MyDataT } from './MyData';

export const metadata = { title: 'Meus dados', description: 'Veja e gerencie os dados da sua conta Raykookie.' };

export default async function Page() {
  await requireCustomer('/cliente/conta');
  const data = await apiGet<MyDataT>('/api/cliente/me/dados', '/cliente/entrar?next=%2Fcliente%2Fconta');
  return (
    <>
      <h1 className="font-display text-3xl font-semibold">Meus dados</h1>
      <p className="mt-1 text-muted">Aqui você vê o que guardamos sobre você e exerce seus direitos da LGPD, sem precisar falar com ninguém.</p>
      <MyData data={data} />
    </>
  );
}
