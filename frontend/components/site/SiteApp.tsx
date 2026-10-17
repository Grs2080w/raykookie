import { Download } from 'lucide-react';
import Link from 'next/link';
import type { AppRelease } from '@shared/types';

const size = (b: number) => (b >= 1048576 ? `${(b / 1048576).toFixed(1).replace('.', ',')} MB` : `${Math.round(b / 1024)} KB`);
const dateBR = (iso: string) => new Date(iso).toLocaleDateString('pt-BR', { timeZone: 'America/Sao_Paulo' });

/**
 * App Android no rodapé da home: um bloco compacto com o botão de baixar; versão, build, tamanho, SHA-256 e instruções
 * ficam recolhidos. Logo abaixo, o texto "Sobre o app" (finalidade do app e uso do login com Google, exigido na
 * verificação do Google) continua visível, só que discreto.
 */
export function SiteApp({ release }: { release?: AppRelease }) {
  return (
    <section id="app" className="rk-app rk-wrap rk-section rk-anchor" aria-labelledby="app-titulo">
      <div className="rk-app__card">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/app/icone-cliente.png" alt="" width={56} height={56} loading="lazy" className="rk-app__icon" />
        <div className="rk-app__text">
          <h2 id="app-titulo">Baixe o app Raykookie</h2>
          <p>Veja o cardápio e faça seus pedidos pelo celular, com o acompanhamento de cada encomenda.</p>
        </div>
        {release ? (
          <a href={release.url} download className="rk-btn rk-app__btn"><Download size={19} aria-hidden />Baixar para Android</a>
        ) : (
          <p className="rk-app__soon">O aplicativo ainda não foi publicado. Em breve por aqui.</p>
        )}
        {release && (
          <details className="rk-app__tech">
            <summary>Versão {release.version} e como instalar</summary>
            <dl>
              <div><dt>Versão</dt><dd>{release.version} (build {release.build})</dd></div>
              <div><dt>Tamanho</dt><dd className="tnum">{size(release.size)}</dd></div>
              <div><dt>Publicado em</dt><dd className="tnum">{dateBR(release.date)}</dd></div>
            </dl>
            <ol>
              <li>Baixe o arquivo e abra-o no celular.</li>
              <li>Se o Android perguntar, permita instalar apps desta origem (navegador ou gerenciador de arquivos).</li>
              <li>Para atualizar, baixe a versão nova e instale por cima: seus dados continuam.</li>
            </ol>
            <p className="rk-app__sha">SHA-256: {release.sha256}</p>
          </details>
        )}
      </div>

      <div id="sobre-o-app" className="rk-app__about rk-anchor">
        <h3>Sobre o app Raykookie</h3>
        <p>O Raykookie é o site e o aplicativo da nossa confeitaria. Com ele você vê o cardápio, faz encomendas de cookies, tortas, brownies e bolos, paga por Pix e acompanha cada etapa do pedido, do preparo até a entrega ou a retirada. Também é por ali que você pode cancelar um pedido, pedir devolução e cuidar dos seus dados.</p>
        <p>Para entrar, você pode criar uma conta com e-mail e senha ou usar o login com Google. O Google serve só para identificar você: usamos seu nome e seu e-mail para criar a conta e preencher o pedido. Não publicamos nada em seu nome, não acessamos outros dados da sua conta Google e não vendemos suas informações. Saiba mais na <Link href="/privacidade" className="rk-link">Política de Privacidade</Link> e nos <Link href="/termos" className="rk-link">Termos de Serviço</Link>.</p>
      </div>
    </section>
  );
}
