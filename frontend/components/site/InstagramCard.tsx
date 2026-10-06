import { Logo } from '@/components/Logo';

const HANDLE = 'raykookies';
const URL = `https://www.instagram.com/${HANDLE}`;

function InstagramGlyph() {
  return (
    <svg viewBox="0 0 24 24" width={15} height={15} fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <rect x="2" y="2" width="20" height="20" rx="5.5" />
      <circle cx="12" cy="12" r="4.2" />
      <circle cx="17.4" cy="6.6" r="1.1" fill="currentColor" stroke="none" />
    </svg>
  );
}

/** Card de "siga no Instagram" — usado no site e espelha o card do app. */
export function InstagramCard() {
  return (
    <a
      href={URL}
      target="_blank"
      rel="noreferrer noopener"
      className="group flex items-center gap-4 rounded-3xl bg-gradient-to-br from-[#f58529] via-[#dd2a7b] to-[#8134af] p-5 text-white shadow-card transition hover:brightness-105 active:scale-[0.99] sm:gap-5 sm:p-6"
    >
      <span className="grid size-16 shrink-0 place-items-center overflow-hidden rounded-full bg-white p-2 shadow-[0_0_0_3px_rgba(255,255,255,0.6)] sm:size-20">
        <Logo variant="simbolo" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-1.5 text-sm font-semibold uppercase tracking-wide text-white/85">
          <InstagramGlyph />
          Instagram
        </span>
        <span className="mt-0.5 block truncate font-display text-xl font-semibold sm:text-2xl">@{HANDLE}</span>
        <span className="mt-1 block text-sm text-white/85">Fotos novas, bastidores e novidades da confeitaria. Vem seguir!</span>
      </span>
      <span className="hidden shrink-0 rounded-full bg-white px-4 py-2 text-sm font-bold text-[#dd2a7b] sm:inline-block">
        Seguir
      </span>
    </a>
  );
}
