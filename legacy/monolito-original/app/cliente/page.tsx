import { redirect } from 'next/navigation';
import { getCustomer } from '@/lib/customer-auth';

// Destino do botão "Fazer pedido" da página inicial.
export default async function Page() {
  redirect((await getCustomer()) ? '/cliente/pedidos' : '/cliente/entrar');
}
