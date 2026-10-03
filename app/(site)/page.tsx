import { ArrowRight, CalendarClock, Heart, ShoppingBag, Star, Wheat } from 'lucide-react';
import type { Metadata } from 'next';
import Link from 'next/link';
import { Logo } from '@/components/Logo';
import { Photo, type PhotoKind } from '@/components/site/Photo';
import { money } from '@/lib/format';
import { LEAD_DAYS, photoKind } from '@/lib/shop';
import { getMenu } from '@/lib/shop-menu';

export const metadata: Metadata = {
  title: { absolute: 'raykookie · Confeitaria feita à mão' },
  description: 'Cookies, tortas, brownies e bolos feitos à mão, sob encomenda. Escolha no cardápio e faça seu pedido online.',
};

const PEDIR = '/cliente';
// o cardápio vem das receitas do painel; a página é refeita a cada 30 s
export const revalidate = 30;

const GALLERY: { kind: PhotoKind; label: string; tall?: boolean }[] = [
  { kind: 'cookie', label: 'Cookies recém-saídos do forno', tall: true },
  { kind: 'bolo', label: 'Bolo decorado para festa' },
  { kind: 'brownie', label: 'Brownies com casquinha crocante' },
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
  { n: '3', t: 'Receba ou retire', d: 'A gente confirma tudo pelo WhatsApp.' },
];

function Stars() {
  return <span className="flex gap-0.5 text-[#f0a020]" aria-label="5 de 5 estrelas">{[0, 1, 2, 3, 4].map((i) => <Star key={i} size={16} fill="currentColor" strokeWidth={0} />)}</span>;
}

const cta = 'inline-flex min-h-12 items-center justify-center gap-2 rounded-2xl bg-pink px-6 text-base font-bold text-on-pink shadow-[0_10px_24px_-10px_var(--pink)] transition hover:bg-pink-strong active:scale-[0.98]';

export default async function Landing() {
  const menu = await getMenu();
  return (
    <div className="brand-scope min-h-dvh overflow-x-clip pb-24 md:pb-0">
      {/* Cabeçalho */}
      <header className="sticky top-0 z-40 border-b border-line bg-rk-claro/90 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-2.5 md:px-8">
          <Link href="/" aria-label="raykookie, início">
            <Logo variant="compacta" tone="claro" imgClassName="h-10 md:hidden" />
            <Logo tone="claro" imgClassName="hidden h-9 md:block" />
          </Link>
          <nav className="hidden items-center gap-7 text-[15px] font-semibold md:flex" aria-label="Seções">
            <a href="#sobre" className="hover:text-pink-strong">Sobre</a>
            <a href="#cardapio" className="hover:text-pink-strong">Cardápio</a>
            <a href="#depoimentos" className="hover:text-pink-strong">Depoimentos</a>
          </nav>
          <Link href={PEDIR} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-pink px-4 text-[15px] font-bold text-on-pink hover:bg-pink-strong"><ShoppingBag size={17} />Fazer pedido</Link>
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
                <Link href={PEDIR} className={cta}>Fazer pedido <ArrowRight size={19} /></Link>
                <a href="#cardapio" className="inline-flex min-h-12 items-center justify-center rounded-2xl border-2 border-rk-escuro/15 bg-white px-6 text-base font-bold hover:border-rk-escuro/40">Ver cardápio</a>
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3" aria-hidden={false}>
              <Photo kind="bolo" label="Bolo decorado" className="row-span-2 min-h-64" />
              <Photo kind="cookie" label="Cookies" className="aspect-square" />
              <Photo kind="brownie" label="Brownies" className="aspect-square" />
            </div>
          </div>
        </section>

        {/* Diferenciais */}
        <section className="border-y border-line bg-white">
          <ul className="mx-auto grid max-w-6xl gap-5 px-4 py-7 sm:grid-cols-3 md:px-8">
            {[
              { i: Heart, t: 'Feito à mão', d: 'Cada receita é preparada do zero, sem pressa.' },
              { i: Wheat, t: 'Ingredientes selecionados', d: 'Chocolate de verdade, manteiga e frutas frescas.' },
              { i: CalendarClock, t: 'Sob encomenda', d: `Peça com pelo menos ${LEAD_DAYS} dias de antecedência.` },
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
            <p className="mt-4 text-muted">A raykookie nasceu na cozinha de casa, com uma receita de cookie e a vontade de adoçar o dia de quem a gente gosta. Hoje continuamos assim: fornadas pequenas, receitas que testamos até ficarem perfeitas e atenção a cada pedido.</p>
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
                <Photo kind={g.kind} label={g.label} className={g.tall ? 'aspect-[3/4] md:h-full md:min-h-96' : 'aspect-[4/3]'} />
              </li>
            ))}
          </ul>
        </section>

        {/* Cardápio: vem das receitas do painel (só foto, nome e preço) */}
        <section id="cardapio" className="scroll-mt-20 mx-auto max-w-6xl px-4 py-14 md:px-8 md:py-20">
          <p className="text-sm font-bold uppercase tracking-wider text-pink-strong">Cardápio</p>
          <h2 className="font-display mt-2 text-3xl font-semibold md:text-4xl">O que tem para hoje (e para o seu dia)</h2>
          {menu.groups.length === 0 ? (
            <p className="mt-6 rounded-3xl border border-dashed border-line px-6 py-12 text-center text-muted">Nosso cardápio está sendo preparado. Volte em breve!</p>
          ) : (
            <>
              {menu.groups.length > 1 && <nav className="-mx-4 mt-5 flex gap-2 overflow-x-auto px-4 pb-1 [&::-webkit-scrollbar]:hidden md:mx-0 md:px-0" aria-label="Categorias do cardápio">
                {menu.groups.map((g) => <a key={g.id} href={`#cat-${g.id}`} className="shrink-0 rounded-full border border-line bg-white px-4 py-2 text-sm font-bold hover:border-pink">{g.name}</a>)}
              </nav>}
              {menu.groups.map((g) => (
                <div key={g.id} id={`cat-${g.id}`} className={`scroll-mt-24 ${menu.groups.length > 1 ? 'pt-10' : 'pt-6'}`}>
                  {menu.groups.length > 1 && <h3 className="font-display text-2xl font-semibold">{g.name}</h3>}
                  <ul className="mt-4 grid gap-3 md:grid-cols-2">
                    {g.items.map((m) => (
                      <li key={m.id} className="flex items-center gap-3.5 rounded-3xl border border-line bg-white p-3 shadow-card">
                        <Photo kind={photoKind(m)} src={m.image} label={m.name} className="size-24 shrink-0 sm:size-28" rounded="rounded-2xl" />
                        <div className="flex min-w-0 flex-1 flex-col gap-2">
                          <p className="text-lg font-bold leading-snug">{m.name}</p>
                          <div className="flex items-center justify-between gap-2">
                            <p className="font-display text-2xl font-semibold tnum">{money(m.price)}</p>
                            <Link href={`/cliente/pedidos/novo?item=${m.id}`} className="inline-flex min-h-11 items-center rounded-xl bg-pink-tint px-4 text-sm font-bold text-pink-strong hover:brightness-95" aria-label={`Pedir ${m.name}`}>Pedir</Link>
                          </div>
                        </div>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
              <p className="mt-8 text-sm text-muted">Precisa de outro sabor, tamanho ou decoração? Conte no campo de observações do pedido.</p>
            </>
          )}
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

        {/* Pedidos */}
        <section id="pedido" className="scroll-mt-20 mx-auto max-w-6xl px-4 py-14 md:px-8 md:py-20">
          <div className="overflow-hidden rounded-[2rem] bg-rk-escuro p-6 text-white md:grid md:grid-cols-[1.1fr_1fr] md:items-center md:gap-10 md:p-12">
            <div>
              <h2 className="font-display text-3xl font-semibold leading-tight md:text-5xl">Faça sua encomenda</h2>
              <p className="mt-3 max-w-md text-white/75">Em poucos minutos o pedido está feito, e você acompanha tudo na sua conta.</p>
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
          <p className="text-sm text-muted">Confeitaria feita à mão · Encomendas com {LEAD_DAYS} dias de antecedência.</p>
        </div>
      </footer>

      {/* Botão fixo no celular */}
      <div className="fixed inset-x-0 bottom-0 z-40 border-t border-line bg-white/95 px-4 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-3 backdrop-blur md:hidden">
        <Link href={PEDIR} className={`${cta} w-full`}><ShoppingBag size={19} />Fazer pedido</Link>
      </div>
    </div>
  );
}
