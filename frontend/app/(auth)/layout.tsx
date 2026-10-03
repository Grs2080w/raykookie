import { redirect } from 'next/navigation';
import { getUser } from '@/lib/server-api';

export const metadata = { robots: { index: false } };
export const dynamic = 'force-dynamic';

export default async function AuthLayout({ children }: { children: React.ReactNode }) {
  if (await getUser()) redirect('/dashboard');
  return children;
}
