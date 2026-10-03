import Link from 'next/link';
import type { Metadata } from 'next';
import { Logo } from '@/components/Logo';
import { getCustomer } from '@/lib/server-api';
import { LogoutButton } from './LogoutButton';

export const dynamic = 'force-dynamic';
export const metadata: Metadata = { robots: { index: false } };

export default async function ClienteLayout({ children }: { children: React.ReactNode }) {
  const customer = await getCustomer();
  return (
    <div className="brand-scope min-h-dvh">
      <header className="sticky top-0 z-30 border-b border-line bg-rk-claro/90 backdrop-blur">
        <div className="mx-auto flex max-w-2xl items-center justify-between gap-3 px-4 py-2.5">
          <Link href="/" aria-label="Raykookie, página inicial"><Logo variant="compacta" tone="claro" imgClassName="h-10" /></Link>
          {customer && (
            <div className="flex min-w-0 items-center gap-2">
              <span className="hidden max-w-40 truncate text-sm font-semibold sm:block">{customer.name.split(' ')[0]}</span>
              <Link href="/cliente/conta" className="inline-flex min-h-10 items-center rounded-lg px-2 text-sm font-semibold hover:text-pink-strong">Meus dados</Link>
              <LogoutButton />
            </div>
          )}
        </div>
      </header>
      <main className="mx-auto max-w-2xl px-4 py-6 pb-28">{children}</main>
      <footer className="mx-auto max-w-2xl px-4 pb-24 text-center text-sm text-muted">
        <Link href="/termos" className="inline-flex min-h-11 items-center px-3 font-semibold hover:text-pink-strong">Termos de Serviço</Link>
        <Link href="/privacidade" className="inline-flex min-h-11 items-center px-3 font-semibold hover:text-pink-strong">Privacidade</Link>
        <Link href="/excluir-conta" className="inline-flex min-h-11 items-center px-3 font-semibold hover:text-pink-strong">Excluir conta</Link>
      </footer>
    </div>
  );
}
