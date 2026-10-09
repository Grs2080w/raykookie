import { Shell } from '@/components/Shell';
import { requireUser } from '@/lib/auth';

export const metadata = { robots: { index: false } };
export const dynamic = 'force-dynamic';

export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const user = await requireUser();
  return <Shell username={user.username}>{children}</Shell>;
}
