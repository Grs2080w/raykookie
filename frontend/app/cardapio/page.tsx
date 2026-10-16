import type { Metadata } from 'next';
import { ShareMenu } from '@/components/ShareMenu';
import { MenuList } from '@/components/site/MenuList';
import { SiteFooter, SiteHeader } from '@/components/site/SiteChrome';
import { apiPublic } from '@/lib/server-api';
import type { Menu } from '@shared/types';
import { leadDaysLabel } from '@shared/shop';

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
    <div className="rk-site">
      <a href="#conteudo" className="rk-skip">Ir para o cardápio</a>
      <SiteHeader orderHref="/cliente/pedidos/novo?from=cardapio" nav={false} />
      <main id="conteudo" className="rk-wrap rk-section" style={{ paddingTop: 'var(--s-7)' }}>
        <div className="rk-menuhead">
          <h1>Assado depois do seu pedido</h1>
          <p>Tudo feito à mão, só sob encomenda. Escolha o que quiser e peça com {leadDaysLabel()}.</p>
          <ShareMenu className="rk-share" />
        </div>
        <MenuList menu={menu} from="cardapio" />
      </main>
      <SiteFooter />
    </div>
  );
}
