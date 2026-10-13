// Separa o painel de gestão do site do cliente por domínio:
//   · GESTAO_HOST (raykookie-gestao.kntap.com.br) serve só a gestão; o resto vai para o site.
//   · o domínio do site (SITE_URL) serve só o site/cliente; a gestão vai para o GESTAO_HOST.
// Outros hosts (localhost, previews da Vercel) ficam como estão, com tudo acessível, para não atrapalhar o dev.
import { NextResponse, type NextRequest } from 'next/server';

const SITE_URL = process.env.SITE_URL || 'https://raykookie.kntap.com.br';
const GESTAO_HOST = process.env.GESTAO_HOST || 'raykookie-gestao.kntap.com.br';
const SITE_HOST = new URL(SITE_URL).host;

/** Primeiro segmento das rotas do painel: o grupo (app) mais o login e o cadastro do (auth). */
const GESTAO_ROUTES = new Set([
  'dashboard', 'categorias', 'configuracoes', 'devolucoes', 'enderecos', 'estoque', 'feedback', 'fornecedores',
  'metas', 'pedidos', 'receitas', 'solicitacoes-privacidade', 'login', 'register', 'cardapio-do-site', 'cupons',
]);

/** O caminho é do painel de gestão? */
function isGestaoPath(pathname: string): boolean {
  return GESTAO_ROUTES.has(pathname.split('/')[1] ?? '');
}

export function middleware(req: NextRequest) {
  const host = (req.headers.get('x-forwarded-host') ?? req.headers.get('host') ?? '').toLowerCase();
  const { pathname, search } = req.nextUrl;

  // Painel: a raiz leva ao início; o que não é do painel vai para o site. Nunca indexar.
  if (host === GESTAO_HOST) {
    if (pathname === '/') return NextResponse.redirect(new URL('/dashboard', req.url));
    if (!isGestaoPath(pathname)) return NextResponse.redirect(new URL(pathname + search, SITE_URL));
    const res = NextResponse.next();
    res.headers.set('X-Robots-Tag', 'noindex, nofollow');
    return res;
  }

  // Site: o painel mora só no GESTAO_HOST.
  if (host === SITE_HOST && isGestaoPath(pathname)) {
    return NextResponse.redirect(new URL(pathname + search, `https://${GESTAO_HOST}`));
  }
  return NextResponse.next();
}

// Fora /api (repassado ao backend), os arquivos internos do Next e qualquer arquivo com extensão (imagens, ícones…).
export const config = { matcher: ['/((?!api/|_next/|.*\\..*).*)'] };
