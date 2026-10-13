import type { Metadata, Viewport } from 'next';
import './globals.css';
import { ToastHost } from '@/components/ui/Toast';
import { Tracker } from '@/components/Tracker';

// Endereço público do site: o og:image precisa ser uma URL absoluta. Defina SITE_URL no .env.
const site = process.env.SITE_URL || 'https://raykookie.kntap.com.br';

export const metadata: Metadata = {
  metadataBase: new URL(site),
  title: { default: 'Raykookie', template: '%s · Raykookie' },
  applicationName: 'Raykookie',
  description: 'Raykookie: confeitaria feita à mão. Veja o cardápio, faça sua encomenda, pague por Pix e acompanhe o pedido pelo site ou pelo app.',
  ...(process.env.GOOGLE_SITE_VERIFICATION ? { verification: { google: process.env.GOOGLE_SITE_VERIFICATION } } : {}),
  icons: {
    icon: [{ url: '/favicon.ico', sizes: '48x48' }, { url: '/favicon.svg', type: 'image/svg+xml' }],
    apple: '/apple-touch-icon.png',
  },
  openGraph: {
    type: 'website', siteName: 'Raykookie', locale: 'pt_BR',
    title: 'Raykookie · Confeitaria feita à mão', description: 'Bolos, cookies e doces feitos à mão.',
    images: [{ url: '/og-image.png', width: 1200, height: 630, alt: 'Raykookie · Confeitaria feita à mão' }],
  },
  twitter: { card: 'summary_large_image', images: ['/og-image.png'] },
};
export const viewport: Viewport = { themeColor: '#d42a76' };

// Define o tema antes da primeira pintura para não piscar.
const themeScript = `try{var t=localStorage.getItem('theme');if(t!=='light'&&t!=='dark'){t=matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'}document.documentElement.dataset.theme=t}catch(e){}`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="pt-BR" suppressHydrationWarning>
      <head><script dangerouslySetInnerHTML={{ __html: themeScript }} /></head>
      <body>
        {children}
        <ToastHost />
        <Tracker />
      </body>
    </html>
  );
}
