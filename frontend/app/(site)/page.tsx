import { ArrowRight, CalendarClock, Heart, ShoppingBag, Star, Wheat } from 'lucide-react';
import type { Metadata } from 'next';
import Link from 'next/link';
import { Logo } from '@/components/Logo';
import { Photo, type PhotoKind } from '@/components/site/Photo';
import { money } from '@/lib/format';
import { leadDaysLabel, photoKind } from '@shared/shop';
import { apiPublic } from '@/lib/server-api';
import { AppDownload } from '@/components/AppDownload';
import { MobileOrderBar } from '@/components/site/MobileOrderBar';
import { MenuList } from '@/components/site/MenuList';
import { InstagramCard } from '@/components/site/InstagramCard';
import { FeedbackForm } from '@/components/site/FeedbackForm';
import { ShareMenu } from '@/components/ShareMenu';
import type { AppReleases, Menu } from '@shared/types';

export const metadata: Metadata = {
  title: { absolute: 'Raykookie · Confeitaria feita à mão' },
  description: 'Cookies, tortas, brownies e bolos feitos à mão, sob encomenda. Escolha no cardápio e faça seu pedido online.',
};

const PEDIR = '/cliente';
// o cardápio vem das receitas do painel; a página é refeita a cada 30 s
export const dynamic = 'force-dynamic'; // o cardápio é buscado do backend (cache de 30 s no fetch)

const GALLERY: { kind: PhotoKind; label: string; tall?: boolean; src?: string }[] = [
  { kind: 'cookie', label: 'Cookies recém-saídos do forno', tall: true, src: '/fotos/cookie-destaque.jpg' },
  { kind: 'bolo', label: 'Bolo decorado para festa' },
  { kind: 'brownie', label: 'Brownies com casquinha crocante', src: '/fotos/brownie-destaque.jpg' },
  { kind: 'torta', label: 'Torta de limão maçaricada', tall: true },
  { kind: 'embalagem', label: 'Caixa de presente' },
  { kind: 'bancada', label: 'Nossa bancada de trabalho' },
];

const REVIEWS = [
  { name: 'Mariana S.', text: 'O bolo chegou lindo e estava ainda mais gostoso do que parecia. Todo mundo na festa pediu o contato!', when: 'Aniversário de 30 anos' },
  { name: 'Carlos e Ana', text: 'Pedimos a caixa de brownies de presente e foi sucesso. Embalagem caprichada, entrega pontual.', when: 'Presente de casamento' },
  { name: 'Patrícia L.', text: 'Os cookies de Ninho com Nutella viciam. Já virou tradição de sexta-feira aqui em casa.', when: 'Cliente frequente' },
];

const STEPS = [
  { n: '1', t: 'Entre ou crie sua conta', d: 'Com o Google ou com e-mail e senha.' },
  { n: '2', t: 'Escolha do cardápio', d: 'Monte o pedido e diga quando quer receber.' },
  { n: '3', t: 'Receba ou retire', d: 'Acompanhe tudo pelo app. Só chamamos no WhatsApp se for necessário.' },
];

function Stars() {
  return <span className="flex gap-0.5 text-[#f0a020]" aria-label="5 de 5 estrelas">{[0, 1, 2, 3, 4].map((i) => <Star key={i} size={16} fill="currentColor" strokeWidth={0} />)}</span>;
}

const cta = 'inline-flex min-h-12 items-center justify-center gap-2 rounded-2xl bg-pink px-6 text-base font-bold text-on-pink shadow-[0_10px_24px_-10px_var(--pink)] transition hover:bg-pink-strong active:scale-[0.98]';

export default async function Landing() {
  const [menu, releases] = await Promise.all([
    apiPublic<Menu>('/api/menu').then((m) => m ?? { items: [], groups: [] }),
    apiPublic<AppReleases>('/api/downloads', 15).then((r) => r ?? {}),
  ]);
  return (
    <div className="brand-scope min-h-dvh overflow-x-clip pb-24 md:pb-0">
      {/* Cabeçalho */}
      <header className="sticky top-0 z-40 border-b border-line bg-rk-claro/90 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-2.5 md:px-8">
          <Link href="/" aria-label="Raykookie, início">
            <Logo variant="compacta" tone="claro" imgClassName="h-10 md:hidden" />
            <Logo tone="claro" imgClassName="hidden h-9 md:block" />
          </Link>
          <nav className="hidden items-center gap-7 text-[15px] font-semibold md:flex" aria-label="Seções">
            <a href="#sobre" className="hover:text-pink-strong">Sobre</a>
            <a href="#cardapio" className="hover:text-pink-strong">Cardápio</a>
            <a href="#depoimentos" className="hover:text-pink-strong">Depoimentos</a>
          </nav>
          <Link href={PEDIR} className="hidden min-h-11 items-center gap-2 rounded-xl bg-pink px-4 text-[15px] md:inline-flex font-bold text-on-pink hover:bg-pink-strong"><ShoppingBag size={17} />Fazer pedido</Link>
        </div>
      </header>

      <main>
        {/* Apresentação */}
        <section className="bg-rk-claro">
          <div className="mx-auto grid max-w-6xl gap-8 px-4 pb-12 pt-8 md:grid-cols-2 md:items-center md:gap-14 md:px-8 md:py-20">
            <div>
              <p className="inline-flex items-center gap-2 rounded-full bg-white px-3.5 py-1.5 text-sm font-semibold text-pink-strong shadow-card"><Heart size={15} fill="currentColor" />Sob encomenda, com carinho</p>
              <h1 className="font-display mt-4 text-[40px] font-semibold leading-[1.05] md:text-6xl">Confeitaria feita à mão, do forno para a sua mesa.</h1>
              <p className="mt-4 max-w-md text-lg text-muted">Cookies, tortas, brownies e bolos preparados em pequenas fornadas, com ingredientes selecionados e muito cuidado em cada detalhe.</p>
              <div className="mt-7 flex flex-col gap-3 sm:flex-row">
                <Link id="cta-hero" href={PEDIR} className={cta}>Fazer pedido <ArrowRight size={19} /></Link>
                <a href="#cardapio" className="inline-flex min-h-12 items-center justify-center rounded-2xl border-2 border-rk-escuro/15 bg-white px-6 text-base font-bold hover:border-rk-escuro/40">Ver cardápio</a>
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3" aria-hidden={false}>
              <Photo kind="bolo" label="Bolo decorado" className="row-span-2 min-h-64" />
              <Photo kind="cookie" src="/fotos/cookie-destaque.jpg" label="Cookies" className="aspect-square" />
              <Photo kind="brownie" src="/fotos/brownie-destaque.jpg" label="Brownies" className="aspect-square" />
            </div>
          </div>
        </section>

        {/* Diferenciais */}
        <section className="border-y border-line bg-white">
          <ul className="mx-auto grid max-w-6xl gap-5 px-4 py-7 sm:grid-cols-3 md:px-8">
            {[
              { i: Heart, t: 'Feito à mão', d: 'Cada receita é preparada do zero, sem pressa.' },
              { i: Wheat, t: 'Ingredientes selecionados', d: 'Chocolate de verdade, manteiga e frutas frescas.' },
              { i: CalendarClock, t: 'Sob encomenda', d: `Peça com pelo menos ${leadDaysLabel()}.` },
            ].map(({ i: Icon, t, d }) => (
              <li key={t} className="flex items-start gap-3.5">
                <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-pink-tint text-pink-strong"><Icon size={22} /></span>
                <div><p className="font-bold">{t}</p><p className="text-sm text-muted">{d}</p></div>
              </li>
            ))}
          </ul>
        </section>

        {/* Sobre */}
        <section id="sobre" className="scroll-mt-20 mx-auto grid max-w-6xl gap-8 px-4 py-14 md:grid-cols-2 md:items-center md:gap-14 md:px-8 md:py-20">
          <Photo kind="bancada" label="Nossa bancada de trabalho" className="aspect-[4/3]" />
          <div>
            <p className="text-sm font-bold uppercase tracking-wider text-pink-strong">Nossa história</p>
            <h2 className="font-display mt-2 text-3xl font-semibold md:text-4xl">Doces simples, feitos com capricho.</h2>
            <p className="mt-4 text-muted">A Raykookie nasceu na cozinha de casa, com uma receita de cookie e a vontade de adoçar o dia de quem a gente gosta. Hoje continuamos assim: fornadas pequenas, receitas que testamos até ficarem perfeitas e atenção a cada pedido.</p>
            <p className="mt-3 text-muted">Trabalhamos só sob encomenda, para que tudo chegue fresquinho. Escolha o que você quer, diga o dia e o resto é com a gente.</p>
          </div>
        </section>

        {/* Galeria */}
        <section aria-labelledby="galeria" className="bg-rk-claro py-14 md:py-20">
          <div className="mx-auto max-w-6xl px-4 md:px-8">
            <h2 id="galeria" className="font-display text-3xl font-semibold md:text-4xl">Um gostinho do que fazemos</h2>
          </div>
          <ul className="mx-auto mt-6 flex max-w-6xl snap-x snap-mandatory gap-3 overflow-x-auto px-4 pb-2 md:grid md:grid-cols-3 md:gap-4 md:overflow-visible md:px-8 [&::-webkit-scrollbar]:hidden">
            {GALLERY.map((g) => (
              <li key={g.label} className={`w-[72%] shrink-0 snap-center sm:w-[44%] md:w-auto ${g.tall ? 'md:row-span-2' : ''}`}>
                <Photo kind={g.kind} src={g.src} label={g.label} className={g.tall ? 'aspect-[3/4] md:h-full md:min-h-96' : 'aspect-[4/3]'} />
              </li>
            ))}
          </ul>
        </section>

        {/* Cardápio: vem das receitas do painel (só foto, nome e preço) */}
        <section id="cardapio" className="scroll-mt-20 mx-auto max-w-6xl px-4 py-14 md:px-8 md:py-20">
          <p className="text-sm font-bold uppercase tracking-wider text-pink-strong">Cardápio</p>
          <h2 className="font-display mt-2 text-3xl font-semibold md:text-4xl">O que tem para hoje (e para o seu dia)</h2>
          <div className="mt-4"><ShareMenu /></div>
          <MenuList menu={menu} from="home" />
        </section>

        {/* Depoimentos */}
        <section id="depoimentos" className="scroll-mt-20 bg-rk-claro py-14 md:py-20">
          <div className="mx-auto max-w-6xl px-4 md:px-8">
            <h2 className="font-display text-3xl font-semibold md:text-4xl">Quem já provou, recomenda</h2>
            <ul className="mt-6 grid gap-4 md:grid-cols-3">
              {REVIEWS.map((r) => (
                <li key={r.name} className="rounded-3xl bg-white p-5 shadow-card">
                  <Stars />
                  <blockquote className="mt-3 text-[15px]">“{r.text}”</blockquote>
                  <p className="mt-4 font-bold">{r.name}</p>
                  <p className="text-sm text-muted">{r.when}</p>
                </li>
              ))}
            </ul>
          </div>
        </section>

        {/* Feedback */}
        <section id="feedback" className="scroll-mt-20 mx-auto max-w-2xl px-4 py-10 md:px-8 md:py-12">
          <FeedbackForm />
        </section>

        {/* Instagram */}
        <section id="instagram" className="scroll-mt-20 mx-auto max-w-6xl px-4 py-10 md:px-8 md:py-12">
          <InstagramCard />
        </section>

        {/* App para baixar */}
        <section id="app" className="scroll-mt-20 mx-auto max-w-6xl px-4 py-14 md:px-8 md:py-16">
          <AppDownload
            release={releases.client} icon="/app/icone-cliente.png" title="Baixe o app Raykookie"
            description="Veja o cardápio e faça seus pedidos pelo celular, com o acompanhamento de cada encomenda."
          />
        </section>

        {/* Sobre o app: finalidade do app e uso do login com Google (exigido na verificação do Google) */}
        <section id="sobre-o-app" className="scroll-mt-20 mx-auto max-w-6xl px-4 py-14 md:px-8 md:py-16">
          <h2 className="font-display text-3xl font-semibold md:text-4xl">Sobre o app Raykookie</h2>
          <div className="mt-4 grid gap-6 text-[15px] md:grid-cols-2 md:text-base">
            <p>O Raykookie é o site e o aplicativo da nossa confeitaria. Com ele você vê o cardápio, faz encomendas de cookies, tortas, brownies e bolos, paga por Pix e acompanha cada etapa do pedido, do preparo até a entrega ou a retirada. Também é por ali que você pode cancelar um pedido, pedir devolução e cuidar dos seus dados.</p>
            <p>Para entrar, você pode criar uma conta com e-mail e senha ou usar o login com Google. O Google serve só para identificar você: usamos seu nome e seu e-mail para criar a conta e preencher o pedido. Não publicamos nada em seu nome, não acessamos outros dados da sua conta Google e não vendemos suas informações. Saiba mais na <Link href="/privacidade" className="font-semibold text-pink-strong underline">Política de Privacidade</Link> e nos <Link href="/termos" className="font-semibold text-pink-strong underline">Termos de Serviço</Link>.</p>
          </div>
        </section>

        {/* Pedidos */}
        <section id="pedido" className="scroll-mt-20 mx-auto max-w-6xl px-4 py-14 md:px-8 md:py-20">
          <div className="overflow-hidden rounded-[2rem] bg-rk-escuro p-6 text-white md:grid md:grid-cols-[1.1fr_1fr] md:items-center md:gap-10 md:p-12">
            <div>
              <h2 className="font-display text-3xl font-semibold leading-tight md:text-5xl">Faça sua encomenda</h2>
              <p className="mt-3 max-w-md text-white/75">Em poucos minutos o pedido está feito, e você acompanha todo o andamento pelo app, na sua conta.</p>
              <Link href={PEDIR} className={`${cta} mt-6 w-full md:w-auto`}>Fazer pedido <ArrowRight size={19} /></Link>
            </div>
            <ol className="mt-8 grid gap-4 md:mt-0">
              {STEPS.map((s) => (
                <li key={s.n} className="flex items-start gap-4 rounded-2xl bg-white/8 p-4">
                  <span className="grid size-9 shrink-0 place-items-center rounded-full bg-pink font-bold text-on-pink">{s.n}</span>
                  <div><p className="font-bold">{s.t}</p><p className="text-sm text-white/70">{s.d}</p></div>
                </li>
              ))}
            </ol>
          </div>
        </section>
      </main>

      <footer className="border-t border-line bg-white">
        <div className="mx-auto flex max-w-6xl flex-col items-center gap-4 px-4 py-10 text-center md:flex-row md:justify-between md:px-8">
          <Logo tone="claro" imgClassName="h-9" />
          <p className="text-sm text-muted">Confeitaria feita à mão · Encomendas com {leadDaysLabel()}.</p>
          <nav aria-label="Documentos legais" className="flex gap-1 text-sm font-semibold">
            <Link href="/termos" className="inline-flex min-h-11 items-center px-3 hover:text-pink-strong">Termos de Serviço</Link>
            <Link href="/privacidade" className="inline-flex min-h-11 items-center px-3 hover:text-pink-strong">Privacidade</Link>
            <Link href="/excluir-conta" className="inline-flex min-h-11 items-center px-3 hover:text-pink-strong">Excluir conta</Link>
          </nav>
        </div>
      </footer>

      {/* Botão fixo no celular */}
      <MobileOrderBar href={PEDIR} targetId="cta-hero" className={cta} />
    </div>
  );
}
