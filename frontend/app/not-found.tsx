import Link from 'next/link';
import '@/app/site.css';
import { SiteFooter, SiteHeader } from '@/components/site/SiteChrome';
import { siteClass } from '@/lib/site-font';

export default function NotFound() {
  return (
    <div className={siteClass}>
      <SiteHeader nav={false} />
      <main className="rk-wrap rk-section rk-notfound">
        <p className="rk-notfound__code" aria-hidden>404</p>
        <h1>Página não encontrada</h1>
        <p>O endereço pode ter mudado ou o item foi excluído.</p>
        <Link href="/" className="rk-btn rk-btn--lg">Voltar ao início</Link>
      </main>
      <SiteFooter />
    </div>
  );
}
