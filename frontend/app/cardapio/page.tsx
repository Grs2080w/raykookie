import type { Metadata } from 'next';
import Link from 'next/link';
import { Logo } from '@/components/Logo';
import { ShareMenu } from '@/components/ShareMenu';
import { MenuList } from '@/components/site/MenuList';
import { apiPublic } from '@/lib/server-api';
import type { Menu } from '@shared/types';

export const dynamic = 'force-dynamic'; // o cardápio vem das receitas do painel

export const metadata: Metadata = {
  title: 'Cardápio',
  description: 'Cookies, tortas, brownies e bolos feitos à mão, sob encomenda. Veja o cardápio e faça seu pedido.',
  alternates: { canonical: '/cardapio' },
  // o openGraph da página substitui o do layout: repete a imagem, senão o link compartilhado sai sem foto
  openGraph: {
    type: 'website', siteName: 'Raykookie', locale: 'pt_BR', title: 'Cardápio · Raykookie', description: 'Veja o cardápio e faça seu pedido.', url: '/cardapio',
    images: [{ url: '/og-image.png', width: 1200, height: 630, alt: 'Raykookie · Confeitaria feita à mão' }],
  },
  twitter: { card: 'summary_large_image', title: 'Cardápio · Raykookie', images: ['/og-image.png'] },
};

/** Página só com o cardápio: é o link que dá para compartilhar. */
export default async function Page() {
  const menu = (await apiPublic<Menu>('/api/menu', 10)) ?? { items: [], groups: [] };
  return (
    <div className="brand-scope min-h-dvh overflow-x-clip">
      <header className="sticky top-0 z-30 border-b border-line bg-rk-claro/90 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-2.5 md:px-8">
          <Link href="/" aria-label="Raykookie, página inicial"><Logo variant="compacta" tone="claro" imgClassName="h-10" /></Link>
          <Link href="/cliente/pedidos/novo?from=cardapio" className="inline-flex min-h-11 items-center rounded-xl bg-pink px-4 text-[15px] font-bold text-on-pink hover:bg-pink-strong">Fazer pedido</Link>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-4 pb-16 pt-10 md:px-8 md:pt-14">
        <p className="text-sm font-bold uppercase tracking-wider text-pink-strong">Cardápio</p>
        <h1 className="font-display mt-2 text-4xl font-semibold leading-tight md:text-5xl">O que tem para hoje (e para o seu dia)</h1>
        <p className="mt-3 max-w-xl text-lg text-muted">Tudo feito à mão, sob encomenda. Escolha o que você quer e peça pelo site ou pelo app.</p>
        <div className="mt-5"><ShareMenu /></div>
        <MenuList menu={menu} from="cardapio" />
      </main>

      <footer className="border-t border-line bg-white">
        <div className="mx-auto flex max-w-6xl flex-col items-center gap-2 px-4 py-8 text-center text-sm text-muted md:flex-row md:justify-between md:px-8">
          <p>Confeitaria feita à mão · Encomendas com 2 dias de antecedência.</p>
          <nav aria-label="Documentos legais" className="flex gap-1 font-semibold">
            <Link href="/termos" className="inline-flex min-h-11 items-center px-3 hover:text-pink-strong">Termos de Serviço</Link>
            <Link href="/privacidade" className="inline-flex min-h-11 items-center px-3 hover:text-pink-strong">Privacidade</Link>
          </nav>
        </div>
      </footer>
    </div>
  );
}
