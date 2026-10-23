import Script from 'next/script';

/** Tag do Google (gtag.js), carregada só nas páginas que a renderizam: hoje, apenas o cardápio. */
const TAG_ID = 'G-NH3VDK7C82';

/** Medição de visitas do Google na página do cardápio (o resto do site não envia nada ao Google). */
export function GoogleTag() {
  return (
    <>
      <Script src={`https://www.googletagmanager.com/gtag/js?id=${TAG_ID}`} strategy="afterInteractive" />
      <Script id="gtag-init" strategy="afterInteractive">
        {`window.dataLayer = window.dataLayer || [];
function gtag(){dataLayer.push(arguments);}
gtag('js', new Date());
gtag('config', '${TAG_ID}');`}
      </Script>
    </>
  );
}
