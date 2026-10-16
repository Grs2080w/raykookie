import type { Metadata } from 'next';
import Link from 'next/link';
import '@fontsource/archivo-black/index.css';
import './landing.css';
import { AppDownload } from '@/components/AppDownload';
import { Logo } from '@/components/Logo';
import { BoxBuilder } from '@/components/site/landing/BoxBuilder';
import { HowItWorks, type Stage } from '@/components/site/landing/HowItWorks';
import { Reveal } from '@/components/site/landing/Reveal';
import { FeedbackForm } from '@/components/site/FeedbackForm';
import { TrackingToggle } from '@/components/site/TrackingToggle';
import { biteVars } from '@/lib/bite';
import { todayBR } from '@/lib/dates';
import { apiPublic } from '@/lib/server-api';
import { LEAD_DAYS, leadDaysLabel } from '@shared/shop';
import { addDays } from '@shared/text';
import type { AppReleases, Menu } from '@shared/types';

export const metadata: Metadata = {
  title: { absolute: 'Raykookie · Cookies e brownies feitos à mão' },
  description: 'Cookies, brownies e bits de brownie assados em fornadas pequenas, sob encomenda. Monte sua caixa no cardápio e peça online.',
  alternates: { canonical: '/' },
};

const PEDIR = '/cliente';
// o cardápio vem das receitas do painel; a página é refeita a cada 30 s
export const dynamic = 'force-dynamic';

const MARQUEE = ['cookies', 'brownies', 'bits de brownie', 'combos', 'feito à mão', 'sob encomenda'];

// Só fotos reais da casa (public/fotos e as do cardápio). Sem foto de banco de imagem.
const STAGES: Stage[] = [
  { name: 'Escolha', text: `Monte a caixa pelo cardápio. Peça com ${leadDaysLabel()}: a fornada é feita para o seu pedido.`, photo: '/fotos/brownie-destaque.jpg', alt: 'Brownies com cobertura de chocolate sobre uma grade' },
  { name: 'Entre', text: 'Com o Google ou com e-mail e senha. É rápido e guarda seus dados para o próximo pedido.', photo: '/fotos/cookie-destaque.jpg', alt: 'Dois cookies sobre papel de cookies' },
  { name: 'Pague', text: 'Pix copia e cola ao finalizar, cartão de crédito ou dinheiro na entrega.', photo: '/fotos/bolo-destaque.jpg', alt: 'Cookie com fios de chocolate sobre papel' },
  { name: 'Receba', text: 'Retire sem taxa ou receba em casa. Acompanhe cada etapa pelo app; só chamamos no WhatsApp se for preciso.', photo: '/fotos/bancada-destaque.jpg', alt: 'Brownies sobre a grade de resfriar' },
];

// Parede de sabores: palavras dos itens do cardápio; a cada quatro células, uma foto real.
const WALL: ({ word: string; cap: string; long?: boolean } | { photo: string; alt: string })[] = [
  { word: 'Nutella', cap: 'cookie e brownie' },
  { photo: '/fotos/cookie-destaque.jpg', alt: 'Cookies com gotas de chocolate' },
  { word: 'Ninho', cap: 'brownie' },
  { word: 'Kinder', cap: 'cookie' },
  { word: 'Kit Kat', cap: 'cookie' },
  { word: 'Baunilha', cap: 'cookie tradicional', long: true },
  { photo: '/fotos/brownie-destaque.jpg', alt: 'Brownies de chocolate sobre a grade' },
  { word: 'Brigadeiro', cap: 'brownie', long: true },
  { word: 'Bits', cap: 'de brownie' },
  { photo: '/fotos/bolo-destaque.jpg', alt: 'Cookie com cobertura de chocolate' },
  { word: 'Combos', cap: 'a partir de R$ 21,50' },
  { word: 'Branco', cap: 'chocolate branco' },
];
const PALETTE = ['p1', 'p2', 'p3', 'p4'];

export default async function Landing() {
  const [menu, releases] = await Promise.all([
    apiPublic<Menu>('/api/menu').then((m) => m ?? { items: [], groups: [] }),
    apiPublic<AppReleases>('/api/downloads', 15).then((r) => r ?? {}),
  ]);
  const earliest = new Date(`${addDays(todayBR(), LEAD_DAYS)}T12:00:00`).toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' });
  const lowest = menu.items.length ? Math.min(...menu.items.map((i) => i.price)) : 0;

  return (
    <div className="rk" style={biteVars()}>
      <script dangerouslySetInnerHTML={{ __html: "document.documentElement.classList.add('rk-js')" }} />
      <Reveal />

      {/* ── Cabeçalho */}
      <header className="rk-bar wrap">
        <Link href="/" aria-label="Raykookie, início">
          <Logo variant="compacta" tone="claro" imgClassName="h-10 md:hidden" />
          <Logo tone="claro" imgClassName="hidden h-9 md:block" />
        </Link>
        <nav aria-label="Seções" className="hidden md:flex">
          <a href="#cardapio">Cardápio</a>
          <a href="#como-funciona">Como funciona</a>
          <a href="#sobre">Sobre</a>
          <a href="#app">App</a>
        </nav>
        <Link href={PEDIR} className="btn">Fazer pedido</Link>
      </header>

      <main>
        {/* ── Hero: o disco do cookie entra pela direita e leva as mordidas */}
        <section className="hero">
          <div className="hero-disc disc" aria-hidden />
          <div className="hero-photo shutter">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <div className="disc-photo whole"><img src="/fotos/cookie-destaque.jpg" alt="Dois cookies com gotas e fios de chocolate sobre papel de cookies" width={1050} height={1400} /></div>
          </div>
          <div className="hero-copy wrap">
            <h1 className="display">Cookie<br />feito à mão</h1>
            <p className="hero-lede">Cookies e brownies assados em fornadas pequenas, só sob encomenda{lowest ? `, a partir de R$ ${lowest.toFixed(2).replace('.', ',')}` : ''}.</p>
            <div className="hero-actions">
              <Link id="cta-hero" href={PEDIR} className="bite-btn">Fazer<br />pedido</Link>
              <a href="#cardapio" className="link">Ver o cardápio</a>
            </div>
          </div>
        </section>

        {/* ── Banda */}
        <div className="band" aria-hidden>
          <div className="band-track display">
            {[0, 1].flatMap((k) => MARQUEE.flatMap((w) => [<span key={`${k}${w}`}>{w}</span>, <i key={`${k}${w}d`} className="band-dot bite" />]))}
          </div>
        </div>

        {/* ── Cardápio e caixa (interativo) */}
        <BoxBuilder menu={menu} delivery={earliest} />

        {/* ── Como funciona */}
        <section id="como-funciona" className="how wrap" aria-labelledby="como-titulo">
          <div className="how-intro">
            <h2 id="como-titulo" className="display" data-reveal>Do forno até você</h2>
            <p data-reveal style={{ '--i': 1 } as React.CSSProperties}>Quatro passos, sem complicação. Toque numa etapa para ver o que acontece nela.</p>
          </div>
          <HowItWorks stages={STAGES} />
        </section>

        {/* ── Sobre */}
        <section id="sobre" className="about" aria-labelledby="sobre-titulo">
          <div className="about-left">
            <h2 id="sobre-titulo" className="display" data-reveal>Começou numa cozinha de casa</h2>
            <div className="about-photo" data-reveal="scale">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <div className="disc-photo bite"><img src="/fotos/bancada-destaque.jpg" alt="Brownies recém-assados esfriando na grade" width={1050} height={1400} loading="lazy" /></div>
            </div>
          </div>
          <div className="about-right">
            <div data-reveal>
              <h3>Uma receita de cookie</h3>
              <p>A Raykookie nasceu com uma receita de cookie e a vontade de adoçar o dia de quem a gente gosta. Continua assim: receitas testadas até ficarem certas, uma fornada de cada vez.</p>
            </div>
            <div data-reveal style={{ '--i': 1 } as React.CSSProperties}>
              <h3>Só sob encomenda</h3>
              <p>Por isso tudo chega fresquinho. Escolha o que quer, diga o dia e o resto é com a gente.</p>
            </div>
            <div data-reveal style={{ '--i': 2 } as React.CSSProperties}><Link href={PEDIR} className="btn">Fazer pedido</Link></div>
          </div>
        </section>

        {/* ── Parede de sabores */}
        <section className="wall" aria-label="Sabores" id="sabores">
          {WALL.map((t, i) =>
            'photo' in t ? (
              <div key={t.photo} className="tile" data-reveal="scale" style={{ '--i': i % 4 } as React.CSSProperties}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={t.photo} alt={t.alt} width={525} height={525} loading="lazy" />
              </div>
            ) : (
              <a key={t.word} href="#cardapio" className={`tile ${PALETTE[i % 4]}`} data-reveal="scale" style={{ '--i': i % 4 } as React.CSSProperties}>
                <span className={`word display${t.long ? ' long' : ''}`}>{t.word}</span>
                <span className="cap">{t.cap}</span>
              </a>
            ),
          )}
        </section>

        {/* ── App */}
        <section id="app" className="app wrap">
          <div className="app-text">
            <h2 className="display" data-reveal>Seu pedido no bolso</h2>
            <p data-reveal style={{ '--i': 1 } as React.CSSProperties}>O Raykookie é o site e o aplicativo da nossa confeitaria. Você vê o cardápio, faz encomendas de cookies e brownies, paga por Pix e acompanha cada etapa do pedido, do preparo até a entrega ou a retirada. Também é por ali que cancela um pedido, pede devolução e cuida dos seus dados.</p>
            <p data-reveal style={{ '--i': 2 } as React.CSSProperties}>Para entrar, use e-mail e senha ou o login com Google. O Google serve só para identificar você: usamos seu nome e e-mail para criar a conta e preencher o pedido. Não publicamos nada em seu nome, não acessamos outros dados da sua conta Google e não vendemos suas informações. Saiba mais na <Link href="/privacidade" className="link">Política de Privacidade</Link> e nos <Link href="/termos" className="link">Termos de Serviço</Link>.</p>
          </div>
          <div data-reveal style={{ '--i': 1 } as React.CSSProperties}>
            <AppDownload flat release={releases.client} icon="/app/icone-cliente.png" title="Baixe o app Raykookie" description="Veja o cardápio e faça seus pedidos pelo celular, com o acompanhamento de cada encomenda." />
          </div>
        </section>

        {/* ── Conversa */}
        <section id="feedback" className="talk wrap">
          <div className="talk-text">
            <h2 className="display" data-reveal>Provou? Conta pra gente</h2>
            <p data-reveal style={{ '--i': 1 } as React.CSSProperties}>Sua opinião, e a foto se quiser, ajuda a melhorar a próxima fornada.</p>
            <a href="https://www.instagram.com/raykookies" target="_blank" rel="noreferrer noopener" className="ig" data-reveal style={{ '--i': 2 } as React.CSSProperties}>
              <span className="disc bite" aria-hidden /><span>Siga @raykookies no Instagram</span>
            </a>
          </div>
          <div data-reveal style={{ '--i': 1 } as React.CSSProperties}><FeedbackForm /></div>
        </section>

        {/* ── Fecho: o cookie volta */}
        <section className="close" aria-labelledby="fecho-titulo" style={{ marginTop: 'var(--gap-section)' }}>
          <div className="close-photo disc-photo bite" data-reveal="scale">
            <video data-drift src="/media/forno.webm" poster="/media/forno-poster.jpg" autoPlay muted loop playsInline preload="metadata" aria-label="Cookies assando no forno" width={854} height={480} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          </div>
          <div className="close-text">
            <h2 id="fecho-titulo" className="display" data-reveal>Seu cookie está no forno</h2>
            <p data-reveal style={{ '--i': 1 } as React.CSSProperties}>Quase. Falta você pedir.</p>
            <p className="fact" data-reveal style={{ '--i': 2 } as React.CSSProperties}>Peça com {leadDaysLabel()}</p>
            <Link href={PEDIR} className="btn btn-ink" data-reveal style={{ '--i': 3 } as React.CSSProperties}>Fazer pedido</Link>
          </div>
        </section>
      </main>

      {/* ── Rodapé: a marca grande */}
      <footer className="foot">
        <div className="foot-mark">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/brand/raykookie-principal-fundo-escuro.svg" alt="raykookie" width={2000} height={380} />
        </div>
        <div className="foot-cols">
          <nav aria-label="Pedidos"><h3>Pedidos</h3><ul><li><a href="#cardapio">Cardápio</a></li><li><a href="#caixa">Sua caixa</a></li><li><a href="#como-funciona">Como funciona</a></li></ul></nav>
          <nav aria-label="A casa"><h3>A casa</h3><ul><li><a href="#sobre">Sobre</a></li><li><a href="#sabores">Sabores</a></li><li><a href="#app">App</a></li></ul></nav>
          <nav aria-label="Documentos legais"><h3>Legal</h3><ul><li><Link href="/termos">Termos de Serviço</Link></li><li><Link href="/privacidade">Privacidade</Link></li><li><Link href="/excluir-conta">Excluir conta</Link></li><li><TrackingToggle /></li></ul></nav>
          <div><h3>Contato</h3><ul><li><a href="https://www.instagram.com/raykookies" target="_blank" rel="noreferrer noopener">@raykookies</a></li><li><Link href="/cliente">Área do cliente</Link></li></ul></div>
        </div>
        <div className="foot-end">
          <p>© {new Date().getFullYear()} Raykookie · Confeitaria feita à mão. Vídeo do forno: “Baking cookies”, CC0, via <a className="link" href="https://commons.wikimedia.org/wiki/File:Baking_cookies.ogv" target="_blank" rel="noreferrer noopener">Wikimedia Commons</a>, não é da Raykookie.</p>
          <Link href={PEDIR} className="btn">Fazer pedido</Link>
        </div>
      </footer>

    </div>
  );
}
