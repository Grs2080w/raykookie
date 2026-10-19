import type { MetadataRoute } from 'next';

/** Web app manifest: nome, cor e ícones para o navegador e para "adicionar à tela inicial". */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: 'Raykookie · Confeitaria feita à mão',
    short_name: 'Raykookie',
    description: 'Cookies, tortas, brownies e bolos feitos à mão, sob encomenda.',
    lang: 'pt-BR',
    start_url: '/',
    display: 'browser',
    theme_color: '#d42a76',
    background_color: '#ffffff',
    icons: [
      { src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml' },
      { src: '/apple-touch-icon.png', sizes: '180x180', type: 'image/png' },
    ],
  };
}
