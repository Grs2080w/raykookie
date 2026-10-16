import { Star } from 'lucide-react';
import type { Metadata } from 'next';
import Link from 'next/link';
import { FeedbackForm } from '@/components/site/FeedbackForm';
import { InstagramCard } from '@/components/site/InstagramCard';
import { MenuList } from '@/components/site/MenuList';
import { SiteFooter, SiteHeader } from '@/components/site/SiteChrome';
import { SiteApp } from '@/components/site/SiteApp';
import { ShareMenu } from '@/components/ShareMenu';
import { niceName } from '@/lib/menu-view';
import { apiPublic } from '@/lib/server-api';
import { leadDaysLabel } from '@shared/shop';
import type { AppReleases, Menu } from '@shared/types';

export const metadata: Metadata = {
  title: { absolute: 'Raykookie · Confeitaria feita à mão' },
  description: 'Cookies, tortas, brownies e bolos feitos à mão, sob encomenda. Escolha no cardápio e faça seu pedido online.',
  alternates: { canonical: '/' },
};

const PEDIR = '/cliente';
// o cardápio vem das receitas do painel; a página é refeita a cada 30 s
export const dynamic = 'force-dynamic'; // o cardápio é buscado do backend (cache de 30 s no fetch)

const DIFERENCIAIS = [
  { t: 'Feito à mão', d: 'Cada receita é preparada do zero, sem pressa.' },
  { t: 'Ingredientes selecionados', d: 'Chocolate de verdade, manteiga e frutas frescas.' },
  { t: 'Sob encomenda', d: `Peça com pelo menos ${leadDaysLabel()}.` },
];

const STEPS = [
  { t: 'Entre ou crie sua conta', d: 'Com o Google ou com e-mail e senha.' },
  { t: 'Escolha do cardápio', d: 'Monte o pedido e diga quando quer receber.' },
  { t: 'Receba ou retire', d: 'Acompanhe tudo pelo app. Só chamamos no WhatsApp se for necessário.' },
];

// Texto fixo no código (não vem do painel). Ver o relatório: precisa ser trocado por depoimentos reais.
const REVIEWS = [
  { name: 'Mariana S.', text: 'O bolo chegou lindo e estava ainda mais gostoso do que parecia. Todo mundo na festa pediu o contato!', when: 'Aniversário de 30 anos' },
  { name: 'Carlos e Ana', text: 'Pedimos a caixa de brownies de presente e foi sucesso. Embalagem caprichada, entrega pontual.', when: 'Presente de casamento' },
  { name: 'Patrícia L.', text: 'Os cookies de Ninho com Nutella viciam. Já virou tradição de sexta-feira aqui em casa.', when: 'Cliente frequente' },
];

// Fotos de vitrine da casa (public/fotos). As do cardápio entram na galeria a partir do próprio cardápio.
const SHOWCASE = { alt: 'Cookie com fios de chocolate sobre papel de cookies', src: '/fotos/bolo-destaque.jpg' };

/** Fotos distintas do cardápio, cada uma com o nome do item (prefere o item avulso ao combo que reaproveita a foto). */
function menuPhotos(menu: Menu): { src: string; name: string }[] {
  const seen = new Map<string, { src: string; name: string; combo: boolean }>();
  for (const i of menu.items) {
    if (!i.image) continue;
    const key = i.image.split('?')[0];
    const cur = seen.get(key);
    if (!cur || (cur.combo && !i.combo)) seen.set(key, { src: i.image, name: niceName(i.name), combo: !!i.combo });
  }
  return [...seen.values()];
}

function Stars() {
  return <span className="rk-stars" role="img" aria-label="5 de 5 estrelas">{[0, 1, 2, 3, 4].map((i) => <Star key={i} size={16} fill="currentColor" strokeWidth={0} aria-hidden />)}</span>;
}

export default async function Landing() {
  const [menu, releases] = await Promise.all([
    apiPublic<Menu>('/api/menu').then((m) => m ?? { items: [], groups: [] }),
    apiPublic<AppReleases>('/api/downloads', 15).then((r) => r ?? {}),
  ]);
  // Galeria: a foto de vitrine grande + as fotos do cardápio. Quatro cabem em linha; cinco ou mais viram mosaico (1 grande + 4 ou + 8).
  const all = [{ src: SHOWCASE.src, alt: SHOWCASE.alt, caption: '' }, ...menuPhotos(menu).map((p) => ({ src: p.src, alt: p.name, caption: p.name }))];
  const take = all.length >= 9 ? 9 : all.length >= 5 ? 5 : all.length;
  const gallery = all.slice(0, take);
  const [lead, ...others] = REVIEWS;

  return (
    <div className="rk-site">
      <a href="#cardapio" className="rk-skip">Ir para o cardápio</a>
      <SiteHeader orderHref={PEDIR} />

      <main>
        {/* ── Apresentação */}
        <section className="rk-hero" aria-labelledby="hero-titulo">
          <div className="rk-wrap rk-hero__grid">
            <div className="rk-hero__copy">
              <h1 id="hero-titulo" className="rk-in" style={{ '--i': 0 } as React.CSSProperties}>Confeitaria feita à mão, do forno para a sua mesa.</h1>
              <p className="rk-hero__lede rk-in" style={{ '--i': 1 } as React.CSSProperties}>Cookies, tortas, brownies e bolos preparados em pequenas fornadas, com ingredientes selecionados e muito cuidado em cada detalhe.</p>
              <div className="rk-hero__actions rk-in" style={{ '--i': 2 } as React.CSSProperties}>
                <Link id="cta-hero" href={PEDIR} className="rk-btn rk-btn--lg">Fazer pedido</Link>
                <a href="#cardapio" className="rk-link">Ver cardápio</a>
              </div>
              <p className="rk-hero__note rk-in" style={{ '--i': 3 } as React.CSSProperties}>Sob encomenda: peça com {leadDaysLabel()}.</p>
            </div>
            <div className="rk-hero__media">
              {/* o símbolo do logo (o cookie com a mordida) aparece grande atrás das fotos */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/brand/raykookie-simbolo-cookie.svg" alt="" width={320} height={320} className="rk-hero__mark" aria-hidden />
              <figure className="rk-arch rk-hero__main rk-in" style={{ '--i': 1 } as React.CSSProperties}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src="/fotos/cookie-destaque.jpg" alt="Dois cookies com gotas e fios de chocolate sobre papel de cookies da Raykookie" width={1050} height={1400} fetchPriority="high" />
              </figure>
              <figure className="rk-hero__small rk-in" style={{ '--i': 3 } as React.CSSProperties}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src="/fotos/brownie-destaque.jpg" alt="Brownies de chocolate sobre uma grade de resfriar" width={1050} height={1400} loading="lazy" decoding="async" />
              </figure>
            </div>
          </div>
        </section>

        {/* ── Diferenciais */}
        <section className="rk-band rk-dark" aria-label="Como trabalhamos">
          <ul className="rk-wrap">
            {DIFERENCIAIS.map((d) => <li key={d.t}><h2>{d.t}</h2><p>{d.d}</p></li>)}
          </ul>
        </section>

        {/* ── Cardápio: vem das receitas do painel */}
        <section id="cardapio" className="rk-wrap rk-section rk-anchor" aria-labelledby="cardapio-titulo">
          <div className="rk-menuhead">
            <h2 id="cardapio-titulo">Assado depois do seu pedido</h2>
            <p>Escolha seus favoritos e peça com {leadDaysLabel()}. Cada fornada é feita para você.</p>
            <ShareMenu className="rk-share" />
          </div>
          <MenuList menu={menu} from="home" />
        </section>

        {/* ── Como pedir */}
        <section id="como-pedir" className="rk-steps rk-dark rk-section rk-anchor" aria-labelledby="pedido-titulo">
          <div className="rk-wrap">
            <div className="rk-steps__head">
              <h2 id="pedido-titulo">Faça sua encomenda</h2>
              <p>Em poucos minutos o pedido está feito, e você acompanha todo o andamento pelo app, na sua conta.</p>
              <Link href={PEDIR} className="rk-btn rk-btn--lg">Fazer pedido</Link>
            </div>
            <ol className="rk-steps__list">
              {STEPS.map((s) => <li key={s.t}><h3>{s.t}</h3><p>{s.d}</p></li>)}
            </ol>
          </div>
        </section>

        {/* ── História */}
        <section id="sobre" className="rk-wrap rk-section rk-anchor rk-story" aria-labelledby="sobre-titulo">
          <figure className="rk-arch rk-reveal">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/fotos/bancada-destaque.jpg" alt="Brownies recém-assados esfriando na grade da nossa bancada" width={1050} height={1400} loading="lazy" decoding="async" />
          </figure>
          <div className="rk-story__text">
            <h2 id="sobre-titulo">Doces simples, feitos com capricho.</h2>
            <p>A Raykookie nasceu na cozinha de casa, com uma receita de cookie e a vontade de adoçar o dia de quem a gente gosta. Hoje continuamos assim: fornadas pequenas, receitas que testamos até ficarem perfeitas e atenção a cada pedido.</p>
            <p>Trabalhamos só sob encomenda, para que tudo chegue fresquinho. Escolha o que você quer, diga o dia e o resto é com a gente.</p>
          </div>
        </section>

        {/* ── Galeria */}
        {gallery.length > 0 && (
          <section id="galeria" className="rk-gallery-section rk-section" aria-labelledby="galeria-titulo">
            <div className="rk-wrap">
              <h2 id="galeria-titulo">Um gostinho do que fazemos</h2>
              <ul className="rk-gallery" data-n={gallery.length} data-layout={gallery.length >= 5 ? 'mosaic' : 'row'}>
                {gallery.map((g) => (
                  <li key={g.src} className="rk-reveal">
                    <figure>
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={g.src} alt={g.alt} width={600} height={600} loading="lazy" decoding="async" />
                      {g.caption && <figcaption>{g.caption}</figcaption>}
                    </figure>
                  </li>
                ))}
              </ul>
            </div>
          </section>
        )}

        {/* ── Depoimentos */}
        <section id="depoimentos" className="rk-wrap rk-section rk-anchor" aria-labelledby="depoimentos-titulo">
          <h2 id="depoimentos-titulo">Quem já provou, recomenda</h2>
          <div className="rk-reviews">
            <figure className="rk-review rk-review--lead">
              <Stars />
              <blockquote>“{lead.text}”</blockquote>
              <figcaption><strong>{lead.name}</strong>{lead.when}</figcaption>
            </figure>
            {others.map((r) => (
              <figure key={r.name} className="rk-review">
                <Stars />
                <blockquote>“{r.text}”</blockquote>
                <figcaption><strong>{r.name}</strong>{r.when}</figcaption>
              </figure>
            ))}
          </div>
        </section>

        {/* ── Feedback e Instagram */}
        <section id="feedback" className="rk-voice rk-section rk-anchor">
          <div className="rk-wrap rk-voice__grid">
            <FeedbackForm />
            <div id="instagram" className="rk-anchor"><InstagramCard /></div>
          </div>
        </section>

        {/* ── App: bloco compacto; os detalhes técnicos ficam recolhidos */}
        <SiteApp release={releases.client} />
      </main>

      <SiteFooter />
    </div>
  );
}
