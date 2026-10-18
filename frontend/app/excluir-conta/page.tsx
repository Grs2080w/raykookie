import type { Metadata } from 'next';
import Link from 'next/link';
import { SiteFooter, SiteHeader } from '@/components/site/SiteChrome';
import { siteClass } from '@/lib/site-font';

export const metadata: Metadata = {
  title: 'Excluir minha conta',
  alternates: { canonical: '/excluir-conta' },
  description: 'Como excluir sua conta Raykookie e o que acontece com os seus dados.',
};

/** Página pública (sem login) que explica a exclusão da conta: exigida pela Google Play e pela LGPD. */
export default function Page() {
  return (
    <div className={siteClass}>
      <a href="#conteudo" className="rk-skip">Ir para o conteúdo</a>
      <SiteHeader nav={false} />
      <main id="conteudo" className="rk-wrap rk-section rk-legal">
        <h1>Excluir minha conta Raykookie</h1>
        <p className="rk-legal__summary">Você pode excluir sua conta a qualquer momento, de graça, direto no aplicativo ou no site.</p>

        <div className="rk-legal__body">
          <section>
            <h2>Como excluir</h2>
            <ol>
              <li>Entre na sua conta no aplicativo Raykookie ou em <Link href="/cliente/conta" className="rk-link">Minha conta → Meus dados</Link>.</li>
              <li>No fim da tela, toque em <b>Excluir minha conta</b>.</li>
              <li>Digite <b>EXCLUIR</b> (e a sua senha, se a conta tiver senha) e confirme.</li>
            </ol>
            <p>Só não é possível excluir enquanto houver pedido em andamento. Depois que for entregue ou cancelado, a exclusão fica liberada.</p>
          </section>

          <section>
            <h2>O que é apagado</h2>
            <ul>
              <li>Nome, e-mail, telefone e endereço.</li>
              <li>Senha e vínculo com a conta Google.</li>
              <li>Todas as suas sessões, em todos os aparelhos.</li>
              <li>Os dados de contato e de endereço dentro dos seus pedidos antigos.</li>
            </ul>
          </section>

          <section>
            <h2>O que é mantido</h2>
            <p>O registro dos pedidos e dos pagamentos (itens, valores, datas e status), <b>sem identificar você</b>, pelo prazo que a lei fiscal e contábil exige.</p>
          </section>

          <section>
            <h2>Sem acesso à conta?</h2>
            <p>Peça pelos canais de atendimento do site, informando o e-mail da conta. Podemos pedir uma confirmação de identidade para proteger você. Veja também a <Link href="/privacidade" className="rk-link">Política de Privacidade</Link>.</p>
          </section>
        </div>
      </main>
      <SiteFooter />
    </div>
  );
}
