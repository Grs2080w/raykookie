import type { Metadata } from 'next';
import '@/app/site.css';
import { SiteFooter, SiteHeader } from '@/components/site/SiteChrome';
import { siteClass } from '@/lib/site-font';

export const dynamic = 'force-dynamic';
export const metadata: Metadata = { title: 'Pagar pedido', description: 'Pague seu pedido da Raykookie com segurança.', robots: { index: false, follow: false } };

export default function PagarLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className={siteClass}>
      <SiteHeader nav={false} />
      <main className="rk-wrap rk-wrap--narrow rk-client" style={{ display: 'grid', gap: 'var(--s-4)', alignContent: 'start' }}>{children}</main>
      <SiteFooter />
    </div>
  );
}
