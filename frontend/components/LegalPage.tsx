import Link from 'next/link';
import { SiteFooter, SiteHeader } from '@/components/site/SiteChrome';
import { LEGAL_IDS, LEGAL_LABEL, LEGAL_PATH, type LegalDoc } from '@shared/legal';

/** Página de um documento legal (Termos de Serviço ou Política de Privacidade), na identidade do site público. */
export function LegalPage({ doc }: { doc: LegalDoc }) {
  const other = LEGAL_IDS.find((id) => id !== doc.id)!;
  return (
    <div className="rk-site">
      <a href="#conteudo" className="rk-skip">Ir para o conteúdo</a>
      <SiteHeader orderHref="/cliente" nav={false} />

      <main id="conteudo" className="rk-wrap rk-section rk-legal">
        <nav aria-label="Documentos legais" className="rk-legal__tabs">
          {LEGAL_IDS.map((id) => (
            <Link key={id} href={LEGAL_PATH[id]} aria-current={id === doc.id ? 'page' : undefined}>{LEGAL_LABEL[id]}</Link>
          ))}
        </nav>

        <article>
          <h1>{doc.title}</h1>
          <p className="rk-legal__summary">{doc.summary}</p>
          <p className="rk-legal__updated">Última atualização: {doc.updated}</p>

          <nav aria-label="Neste documento" className="rk-legal__toc">
            <h2>Neste documento</h2>
            <ol>
              {doc.sections.map((s, i) => <li key={s.h}><a href={`#s${i + 1}`}>{s.h}</a></li>)}
            </ol>
          </nav>

          <div className="rk-legal__body">
            {doc.sections.map((s, i) => (
              <section key={s.h} id={`s${i + 1}`} className="rk-anchor">
                <h2>{s.h}</h2>
                {s.p?.map((t) => <p key={t}>{t}</p>)}
                {s.ul && <ul>{s.ul.map((t) => <li key={t}>{t}</li>)}</ul>}
              </section>
            ))}
          </div>
        </article>

        <p className="rk-legal__more">Veja também: <Link href={LEGAL_PATH[other]} className="rk-link">{LEGAL_LABEL[other]}</Link>.</p>
      </main>

      <SiteFooter />
    </div>
  );
}
