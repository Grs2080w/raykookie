import Link from 'next/link';
import { Logo } from '@/components/Logo';
import { LEGAL_IDS, LEGAL_LABEL, LEGAL_PATH, type LegalDoc } from '@shared/legal';

/** Página de um documento legal (Termos de Serviço ou Política de Privacidade). */
export function LegalPage({ doc }: { doc: LegalDoc }) {
  const other = LEGAL_IDS.find((id) => id !== doc.id)!;
  return (
    <div className="brand-scope min-h-dvh">
      <header className="sticky top-0 z-30 border-b border-line bg-rk-claro/90 backdrop-blur">
        <div className="mx-auto flex max-w-3xl items-center justify-between gap-3 px-4 py-2.5">
          <Link href="/" aria-label="Raykookie, página inicial"><Logo variant="compacta" tone="claro" imgClassName="h-10" /></Link>
          <Link href="/" className="inline-flex min-h-11 items-center text-sm font-semibold hover:text-pink-strong">Voltar ao início</Link>
        </div>
      </header>

      <main className="mx-auto max-w-3xl px-4 pb-16 pt-8 md:pt-12">
        <nav aria-label="Documentos legais" className="mb-6 flex flex-wrap gap-2 text-sm font-semibold">
          {LEGAL_IDS.map((id) => (
            <Link key={id} href={LEGAL_PATH[id]} aria-current={id === doc.id ? 'page' : undefined}
              className={id === doc.id ? 'inline-flex min-h-11 items-center rounded-full bg-ink px-4 text-white' : 'inline-flex min-h-11 items-center rounded-full border border-line bg-white px-4 hover:border-ink/30'}>
              {LEGAL_LABEL[id]}
            </Link>
          ))}
        </nav>

        <article>
          <h1 className="font-display text-[34px] font-semibold leading-tight md:text-5xl">{doc.title}</h1>
          <p className="mt-3 text-lg text-muted">{doc.summary}</p>
          <p className="mt-2 text-sm text-muted">Última atualização: {doc.updated}</p>

          <nav aria-label="Neste documento" className="mt-8 rounded-2xl border border-line bg-white p-5">
            <p className="text-sm font-bold">Neste documento</p>
            <ol className="mt-2 grid gap-1 text-[15px] sm:grid-cols-2">
              {doc.sections.map((s, i) => (
                <li key={s.h}><a href={`#s${i + 1}`} className="inline-flex min-h-9 items-center text-pink-strong underline-offset-4 hover:underline">{s.h}</a></li>
              ))}
            </ol>
          </nav>

          <div className="mt-10 space-y-9">
            {doc.sections.map((s, i) => (
              <section key={s.h} id={`s${i + 1}`} className="scroll-mt-20">
                <h2 className="font-display text-2xl font-semibold leading-snug">{s.h}</h2>
                {s.p?.map((t) => <p key={t} className="mt-3 text-[16px] leading-relaxed">{t}</p>)}
                {s.ul && (
                  <ul className="mt-3 list-disc space-y-2 pl-5 text-[16px] leading-relaxed marker:text-pink">
                    {s.ul.map((t) => <li key={t}>{t}</li>)}
                  </ul>
                )}
              </section>
            ))}
          </div>
        </article>

        <p className="mt-14 border-t border-line pt-6 text-sm text-muted">
          Veja também: <Link href={LEGAL_PATH[other]} className="font-semibold text-pink-strong underline-offset-4 hover:underline">{LEGAL_LABEL[other]}</Link>.
        </p>
      </main>
    </div>
  );
}
