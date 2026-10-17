const HANDLE = 'raykookies';
const URL = `https://www.instagram.com/${HANDLE}`;

function InstagramGlyph() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <rect x="2" y="2" width="20" height="20" rx="5.5" />
      <circle cx="12" cy="12" r="4.2" />
      <circle cx="17.4" cy="6.6" r="1.1" fill="currentColor" stroke="none" />
    </svg>
  );
}

/** "Siga no Instagram": bloco escuro com o símbolo da marca; o link inteiro abre o perfil. */
export function InstagramCard() {
  return (
    <a href={URL} target="_blank" rel="noreferrer noopener" className="rk-ig rk-dark">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/brand/raykookie-simbolo-cookie.svg" alt="" width={64} height={64} loading="lazy" className="rk-ig__mark" />
      <span className="rk-ig__text">
        <span className="rk-ig__handle"><InstagramGlyph />@{HANDLE}</span>
        <span>Fotos novas, bastidores e novidades da confeitaria. Vem seguir!</span>
      </span>
      <span className="rk-ig__go">Seguir</span>
    </a>
  );
}
