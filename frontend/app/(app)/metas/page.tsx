import { PageHeader } from '@/components/PageHeader';
import { monthKeyBR } from '@/lib/dates';
import { apiGet } from '@/lib/server-api';
import { isoDate } from '@shared/text';
import type { Goal, MonthStats } from '@shared/types';
import { GoalsBoard } from './GoalsBoard';

export const metadata = { title: 'Metas' };

export default async function Page({ searchParams }: { searchParams: Promise<{ m?: string }> }) {
  const { m } = await searchParams;
  const month = (m && isoDate(`${m}-01`)) || monthKeyBR();
  const { goal, stats } = await apiGet<{ goal: Goal; stats: MonthStats }>(`/api/goals?month=${month}`);
  return (
    <>
      <PageHeader title="Metas de venda" subtitle="Defina o alvo do mês. O realizado vem dos seus pedidos, sem digitar de novo." />
      <GoalsBoard key={month} month={month} goal={goal} stats={stats} />
    </>
  );
}
