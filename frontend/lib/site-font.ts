import localFont from 'next/font/local';

/** Fonte de título do site público (Young Serif, subset latin). O next/font a pré-carrega e ajusta a fonte reserva para não pular o layout. */
const display = localFont({
  src: '../node_modules/@fontsource/young-serif/files/young-serif-latin-400-normal.woff2',
  weight: '400',
  display: 'swap',
  variable: '--font-site-display',
  adjustFontFallback: 'Times New Roman',
  fallback: ['ui-serif', 'Georgia', 'serif'],
});

/** Classe da raiz do site público: ativa o escopo `.rk-site` e entrega a fonte de título. */
export const siteClass = `rk-site ${display.variable}`;
