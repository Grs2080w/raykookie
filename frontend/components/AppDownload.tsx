import { Download, ShieldCheck, Smartphone } from 'lucide-react';
import type { AppRelease } from '@shared/types';

const size = (b: number) => (b >= 1048576 ? `${(b / 1048576).toFixed(1).replace('.', ',')} MB` : `${Math.round(b / 1024)} KB`);
const dateBR = (iso: string) => new Date(iso).toLocaleDateString('pt-BR', { timeZone: 'America/Sao_Paulo' });

/** Cartão de download de um app Android, com a versão publicada (vinda de downloads/manifest.json). */
export function AppDownload({ release, title, description, icon, id, dark, flat }: { release?: AppRelease; title: string; description: string; icon: string; id?: string; dark?: boolean; flat?: boolean }) {
  return (
    <section id={id} className={`rounded-3xl p-5 sm:p-6 ${flat ? 'bg-[#fbe9f0]' : dark ? 'border border-rk-escuro bg-rk-escuro text-white' : 'border border-line bg-surface shadow-card'}`}>
      <div className="flex items-start gap-4">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={icon} alt="" width={64} height={64} className={`size-16 shrink-0 rounded-2xl ${flat ? '' : 'shadow-card'}`} />
        <div className="min-w-0 flex-1">
          <h2 className="font-display text-2xl font-semibold leading-tight">{title}</h2>
          <p className={`mt-1 text-sm ${dark ? 'text-white/70' : 'text-muted'}`}>{description}</p>
        </div>
      </div>

      {release ? (
        <>
          <dl className={`mt-4 flex flex-wrap gap-x-6 gap-y-1 text-sm ${dark ? 'text-white/80' : 'text-muted'}`}>
            <div><dt className="inline">Versão </dt><dd className="inline font-bold text-inherit"><span className={dark ? 'text-white' : 'text-ink'}>{release.version}</span> <span className="font-normal">(build {release.build})</span></dd></div>
            <div><dt className="inline">Tamanho </dt><dd className="inline tnum">{size(release.size)}</dd></div>
            <div><dt className="inline">Publicado em </dt><dd className="inline tnum">{dateBR(release.date)}</dd></div>
          </dl>
          <a
            href={release.url} download
            className="mt-4 inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-2xl bg-pink px-6 text-base font-bold text-on-pink transition hover:bg-pink-strong active:scale-[0.98] sm:w-auto"
          >
            <Download size={19} />Baixar para Android
          </a>
          <details className={`mt-3 text-sm ${dark ? 'text-white/70' : 'text-muted'}`}>
            <summary className="flex cursor-pointer items-center gap-2 font-semibold"><Smartphone size={16} />Como instalar</summary>
            <ol className="mt-2 list-decimal space-y-1 pl-5">
              <li>Baixe o arquivo e abra-o no celular.</li>
              <li>Se o Android perguntar, permita instalar apps desta origem (navegador ou gerenciador de arquivos).</li>
              <li>Para atualizar, baixe a versão nova e instale por cima: seus dados continuam.</li>
            </ol>
            <p className="mt-2 flex items-start gap-2 break-all text-sm"><ShieldCheck size={15} className="mt-0.5 shrink-0" />SHA-256: {release.sha256}</p>
          </details>
        </>
      ) : (
        <p className={`mt-4 rounded-2xl px-4 py-3 text-sm ${dark ? 'bg-white/10 text-white/80' : 'bg-surface-2 text-muted'}`}>O aplicativo ainda não foi publicado. Em breve por aqui.</p>
      )}
    </section>
  );
}
