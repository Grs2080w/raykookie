import Link from 'next/link';
import type { Metadata } from 'next';
import '@/app/site.css';
import { Logo } from '@/components/Logo';
import { SiteFooter } from '@/components/site/SiteChrome';
import { getCustomer } from '@/lib/server-api';
import { siteClass } from '@/lib/site-font';
import { LogoutButton } from './LogoutButton';

export const dynamic = 'force-dynamic';
export const metadata: Metadata = { robots: { index: false } };

/** Área do cliente: a mesma identidade do site público, em coluna estreita (feita para o celular). */
export default async function ClienteLayout({ children }: { children: React.ReactNode }) {
  const customer = await getCustomer();
  return (
    <div className={siteClass}>
      <header className="rk-header">
        <div className="rk-wrap rk-wrap--narrow">
          <Link href="/" aria-label="Raykookie, página inicial"><Logo variant="principal" tone="claro" imgClassName="h-7 md:h-8" /></Link>
          {customer && (
            <div className="rk-header__links">
              <span className="rk-header__name">{customer.name.split(' ')[0]}</span>
              <Link href="/cliente/conta">Meus dados</Link>
              <LogoutButton />
            </div>
          )}
        </div>
      </header>
      <main className="rk-wrap rk-wrap--narrow rk-client">{children}</main>
      <SiteFooter />
    </div>
  );
}
