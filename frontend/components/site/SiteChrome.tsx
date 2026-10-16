import Link from 'next/link';
import '@/app/site.css';
import { Logo } from '@/components/Logo';
import { TrackingToggle } from '@/components/site/TrackingToggle';
import { leadDaysLabel } from '@shared/shop';

const NAV = [
  { href: '/#cardapio', label: 'Cardápio' },
  { href: '/#como-pedir', label: 'Como pedir' },
  { href: '/#sobre', label: 'Nossa história' },
  { href: '/#depoimentos', label: 'Depoimentos' },
];

/** Cabeçalho do site público: logo, atalhos das seções (só em telas largas) e o botão de pedir. */
export function SiteHeader({ orderHref, nav = true }: { orderHref: string; nav?: boolean }) {
  return (
    <header className="rk-header">
      <div className="rk-wrap">
        <Link href="/" aria-label="Raykookie, início"><Logo variant="principal" tone="claro" imgClassName="h-7 md:h-8" /></Link>
        {nav && (
          <nav aria-label="Seções">
            {NAV.map((n) => <a key={n.href} href={n.href}>{n.label}</a>)}
          </nav>
        )}
        <Link href={orderHref} className="rk-btn rk-btn--sm">Fazer pedido</Link>
      </div>
    </header>
  );
}

/** Rodapé do site público: a marca, o prazo de encomenda e os documentos legais. */
export function SiteFooter() {
  return (
    <footer className="rk-footer rk-dark">
      <div className="rk-wrap">
        <div className="rk-footer__text">
          <Logo variant="compacta" tone="escuro" imgClassName="h-10" />
          <p style={{ marginTop: 'var(--s-3)' }}>Confeitaria feita à mão. Encomendas com {leadDaysLabel()}.</p>
        </div>
        <nav aria-label="Documentos legais">
          <Link href="/termos">Termos de Serviço</Link>
          <Link href="/privacidade">Privacidade</Link>
          <Link href="/excluir-conta">Excluir conta</Link>
          <TrackingToggle className="rk-footer__toggle" />
        </nav>
      </div>
    </footer>
  );
}
