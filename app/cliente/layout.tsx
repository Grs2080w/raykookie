import Link from 'next/link';
import type { Metadata } from 'next';
import { Logo } from '@/components/Logo';
import { getCustomer } from '@/lib/customer-auth';
import { LogoutButton } from './LogoutButton';

export const dynamic = 'force-dynamic';
export const metadata: Metadata = { robots: { index: false } };

export default async function ClienteLayout({ children }: { children: React.ReactNode }) {
  const customer = await getCustomer();
  return (
    <div className="brand-scope min-h-dvh">
      <header className="sticky top-0 z-30 border-b border-line bg-rk-claro/90 backdrop-blur">
        <div className="mx-auto flex max-w-2xl items-center justify-between gap-3 px-4 py-2.5">
          <Link href="/" aria-label="raykookie, página inicial"><Logo variant="compacta" tone="claro" imgClassName="h-10" /></Link>
          {customer && (
            <div className="flex min-w-0 items-center gap-2">
              <span className="hidden max-w-40 truncate text-sm font-semibold sm:block">{customer.name.split(' ')[0]}</span>
              <LogoutButton />
            </div>
          )}
        </div>
      </header>
      <main className="mx-auto max-w-2xl px-4 py-6 pb-28">{children}</main>
    </div>
  );
}
