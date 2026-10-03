import { redirect } from 'next/navigation';
import { apiPublic, getCustomer } from '@/lib/server-api';
import { safeNext } from '@shared/shop';
import { CustomerAuth } from './CustomerAuth';

export const metadata = { title: 'Entrar para pedir' };

export default async function Page({ searchParams }: { searchParams: Promise<{ next?: string; erro?: string }> }) {
  const { next, erro } = await searchParams;
  const dest = safeNext(next);
  if (await getCustomer()) redirect(dest);
  const googleEnabled = !!(await apiPublic<{ googleEnabled: boolean }>('/api/cliente/config', 5))?.googleEnabled;
  return <CustomerAuth next={dest} erro={erro} googleEnabled={googleEnabled} />;
}
