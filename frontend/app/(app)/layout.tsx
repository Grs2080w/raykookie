import { Shell } from '@/components/Shell';
import { requireUser } from '@/lib/server-api';

export const metadata = {
  robots: { index: false },
  title: { default: 'raykookie business', template: '%s · raykookie business' },
  applicationName: 'raykookie business',
  description: 'Painel da confeiteira: estoque, receitas, pedidos, metas e custos.',
  openGraph: { siteName: 'raykookie business', title: 'raykookie business' },
};
export const dynamic = 'force-dynamic';

export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const user = await requireUser();
  return <Shell username={user.username}>{children}</Shell>;
}
