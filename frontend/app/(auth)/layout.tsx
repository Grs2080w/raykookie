import { redirect } from 'next/navigation';
import { getUser } from '@/lib/server-api';

export const metadata = {
  robots: { index: false },
  title: { default: 'raykookie business', template: '%s · raykookie business' },
  applicationName: 'raykookie business',
  description: 'Painel da confeiteira: estoque, receitas, pedidos, metas e custos.',
  openGraph: { siteName: 'raykookie business', title: 'raykookie business' },
};
export const dynamic = 'force-dynamic';

export default async function AuthLayout({ children }: { children: React.ReactNode }) {
  if (await getUser()) redirect('/dashboard');
  return children;
}
