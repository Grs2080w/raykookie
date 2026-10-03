import type { Metadata } from 'next';
import Link from 'next/link';
import { Logo } from '@/components/Logo';

export const metadata: Metadata = {
  title: 'Excluir minha conta',
  description: 'Como excluir sua conta Raykookie e o que acontece com os seus dados.',
};

/** Página pública (sem login) que explica a exclusão da conta: exigida pela Google Play e pela LGPD. */
export default function Page() {
  return (
    <div className="brand-scope min-h-dvh">
      <header className="sticky top-0 z-30 border-b border-line bg-rk-claro/90 backdrop-blur">
        <div className="mx-auto flex max-w-3xl items-center justify-between gap-3 px-4 py-2.5">
          <Link href="/" aria-label="Raykookie, página inicial"><Logo variant="compacta" tone="claro" imgClassName="h-10" /></Link>
          <Link href="/" className="inline-flex min-h-11 items-center text-sm font-semibold hover:text-pink-strong">Voltar ao início</Link>
        </div>
      </header>
      <main className="mx-auto max-w-3xl px-4 pb-16 pt-8 md:pt-12">
        <h1 className="font-display text-[34px] font-semibold leading-tight md:text-5xl">Excluir minha conta Raykookie</h1>
        <p className="mt-3 text-lg text-muted">Você pode excluir sua conta a qualquer momento, de graça, direto no aplicativo ou no site.</p>

        <h2 className="mt-10 font-display text-2xl font-semibold">Como excluir</h2>
        <ol className="mt-3 list-decimal space-y-2 pl-5 text-[16px] leading-relaxed marker:font-bold marker:text-pink">
          <li>Entre na sua conta no aplicativo Raykookie ou em <Link href="/cliente/conta" className="font-semibold text-pink-strong underline">Minha conta → Meus dados</Link>.</li>
          <li>No fim da tela, toque em <b>Excluir minha conta</b>.</li>
          <li>Digite <b>EXCLUIR</b> (e a sua senha, se a conta tiver senha) e confirme.</li>
        </ol>
        <p className="mt-3 text-[16px] leading-relaxed">Só não é possível excluir enquanto houver pedido em andamento. Depois que for entregue ou cancelado, a exclusão fica liberada.</p>

        <h2 className="mt-10 font-display text-2xl font-semibold">O que é apagado</h2>
        <ul className="mt-3 list-disc space-y-2 pl-5 text-[16px] leading-relaxed marker:text-pink">
          <li>Nome, e-mail, telefone e endereço.</li>
          <li>Senha e vínculo com a conta Google.</li>
          <li>Todas as suas sessões, em todos os aparelhos.</li>
          <li>Os dados de contato e de endereço dentro dos seus pedidos antigos.</li>
        </ul>

        <h2 className="mt-10 font-display text-2xl font-semibold">O que é mantido</h2>
        <p className="mt-3 text-[16px] leading-relaxed">O registro dos pedidos e dos pagamentos (itens, valores, datas e status), <b>sem identificar você</b>, pelo prazo que a lei fiscal e contábil exige.</p>

        <h2 className="mt-10 font-display text-2xl font-semibold">Sem acesso à conta?</h2>
        <p className="mt-3 text-[16px] leading-relaxed">Peça pelos canais de atendimento do site, informando o e-mail da conta. Podemos pedir uma confirmação de identidade para proteger você. Veja também a <Link href="/privacidade" className="font-semibold text-pink-strong underline">Política de Privacidade</Link>.</p>
      </main>
    </div>
  );
}
