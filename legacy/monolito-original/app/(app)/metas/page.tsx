import { PageHeader } from '@/components/PageHeader';
import { isoDate } from '@/lib/api';
import { requireUser } from '@/lib/auth';
import { getGoal, getMonthStats } from '@/lib/queries';
import { GoalsBoard } from './GoalsBoard';

export const metadata = { title: 'Metas' };

export default async function Page({ searchParams }: { searchParams: Promise<{ m?: string }> }) {
  const { m } = await searchParams;
  const user = await requireUser();
  const now = new Date();
  const month = (m && isoDate(`${m}-01`)) || `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`;
  const [goal, stats] = await Promise.all([getGoal(user.id, month), getMonthStats(user.id, month)]);
  return (
    <>
      <PageHeader title="Metas de venda" subtitle="Defina o alvo do mês. O realizado vem dos seus pedidos, sem digitar de novo." />
      <GoalsBoard key={month} month={month} goal={goal} stats={stats} />
    </>
  );
}
