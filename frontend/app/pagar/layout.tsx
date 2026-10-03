import Link from 'next/link';
import type { Metadata } from 'next';
import { Logo } from '@/components/Logo';

export const dynamic = 'force-dynamic';
export const metadata: Metadata = { title: 'Pagar pedido', robots: { index: false, follow: false } };

export default function PagarLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="brand-scope min-h-dvh">
      <header className="border-b border-line bg-rk-claro/90">
        <div className="mx-auto flex max-w-2xl items-center px-4 py-2.5">
          <Link href="/" aria-label="Raykookie, página inicial"><Logo variant="compacta" tone="claro" imgClassName="h-10" /></Link>
        </div>
      </header>
      <main className="mx-auto max-w-2xl space-y-4 px-4 py-6">{children}</main>
    </div>
  );
}
