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

/** Fonte de texto e preços (Figtree variável, subset latin): mesma face do app, mas com a reserva ajustada para páginas longas (termos) não pularem na troca. */
const text = localFont({
  src: '../node_modules/@fontsource-variable/figtree/files/figtree-latin-wght-normal.woff2',
  weight: '300 900',
  display: 'swap',
  variable: '--font-site-text',
  adjustFontFallback: 'Arial',
  fallback: ['ui-sans-serif', 'system-ui', 'sans-serif'],
});

/** Classe da raiz do site público: ativa o escopo `.rk-site` e entrega as duas fontes. */
export const siteClass = `rk-site ${display.variable} ${text.variable}`;
