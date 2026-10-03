import { redirect } from 'next/navigation';
import { getCustomer } from '@/lib/customer-auth';
import { safeNext } from '@/lib/shop';
import { CustomerAuth } from './CustomerAuth';

export const metadata = { title: 'Entrar para pedir' };

export default async function Page({ searchParams }: { searchParams: Promise<{ next?: string; erro?: string }> }) {
  const { next, erro } = await searchParams;
  const dest = safeNext(next);
  if (await getCustomer()) redirect(dest);
  const googleEnabled = !!(process.env.GOOGLE_CLIENT_ID && process.env.GOOGLE_CLIENT_SECRET);
  return <CustomerAuth next={dest} erro={erro} googleEnabled={googleEnabled} />;
}
