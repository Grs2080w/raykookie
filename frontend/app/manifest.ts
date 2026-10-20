import type { MetadataRoute } from 'next';
import { headers } from 'next/headers';
import { GESTAO_HOST } from '@/lib/urls';

/** Web app manifest: nome, cor e ícones para o navegador e para "adicionar à tela inicial". O painel (GESTAO_HOST) tem o seu. */
export default async function manifest(): Promise<MetadataRoute.Manifest> {
  const h = await headers();
  const host = (h.get('x-forwarded-host') ?? h.get('host') ?? '').toLowerCase();
  if (host === GESTAO_HOST) {
    return {
      name: 'raykookie business',
      short_name: 'raykookie business',
      description: 'Painel da confeiteira: estoque, receitas, pedidos, metas e custos.',
      lang: 'pt-BR',
      start_url: '/dashboard',
      display: 'browser',
      theme_color: '#d42a76',
      background_color: '#130e12',
      icons: [
        { src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml' },
        { src: '/apple-touch-icon.png', sizes: '180x180', type: 'image/png' },
      ],
    };
  }
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
