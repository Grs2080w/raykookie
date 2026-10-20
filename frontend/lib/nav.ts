import { BarChart3, BookOpen, Calculator, ChefHat, ClipboardList, LayoutDashboard, MapPin, MessageSquareHeart, Package, Settings, ShieldCheck, Store, Tags, Target, TicketPercent, Undo2, type LucideIcon } from 'lucide-react';

/** Uma aba do painel. `bar`: posição na barra inferior do celular (1 a 5); sem `bar`, só aparece no menu. */
export type NavItem = { href: string; label: string; icon: LucideIcon; bar?: number };
export type NavSection = { id: string; title: string; items: NavItem[] };

/** Item solto no topo da sidebar, fora das seções. */
export const NAV_HOME: NavItem = { href: '/dashboard', label: 'Início', icon: LayoutDashboard, bar: 1 };

/**
 * Árvore do menu: usada pela sidebar (desktop) e pelo menu do celular (gaveta). Para criar uma aba, é só
 * acrescentar o item na seção certa; os caminhos (href) são as rotas de `app/(app)`.
 */
export const NAV_SECTIONS: NavSection[] = [
  {
    id: 'producao',
    title: 'Produção e catálogo',
    items: [
      { href: '/receitas', label: 'Receitas', icon: ChefHat, bar: 3 },
      { href: '/cardapio-do-site', label: 'Cardápio', icon: BookOpen, bar: 4 },
      { href: '/categorias', label: 'Categorias', icon: Tags },
    ],
  },
  {
    id: 'estoque',
    title: 'Estoque e compras',
    items: [
      { href: '/estoque', label: 'Estoque', icon: Package, bar: 2 },
      { href: '/fornecedores', label: 'Fornecedores', icon: Store },
    ],
  },
  {
    id: 'vendas',
    title: 'Vendas e pedidos',
    items: [
      { href: '/pedidos', label: 'Pedidos', icon: ClipboardList, bar: 5 },
      { href: '/cupons', label: 'Cupons', icon: TicketPercent },
      { href: '/devolucoes', label: 'Devoluções', icon: Undo2 },
      { href: '/enderecos', label: 'Endereço de retirada', icon: MapPin },
    ],
  },
  {
    id: 'clientes',
    title: 'Clientes',
    items: [
      { href: '/feedback', label: 'Feedback', icon: MessageSquareHeart },
      { href: '/engajamento', label: 'Engajamento', icon: BarChart3 },
      { href: '/solicitacoes-privacidade', label: 'Privacidade dos clientes', icon: ShieldCheck },
    ],
  },
  {
    id: 'planejamento',
    title: 'Planejamento e finanças',
    items: [
      { href: '/metas', label: 'Metas', icon: Target },
      { href: '/configuracoes', label: 'Custos e ajustes', icon: Settings },
      { href: '/prospeccao', label: 'Prospecção', icon: Calculator },
    ],
  },
];

/** Abas da barra inferior do celular, na ordem de `bar`. */
export const NAV_BAR: NavItem[] = [NAV_HOME, ...NAV_SECTIONS.flatMap((s) => s.items)].filter((i) => i.bar).sort((a, b) => a.bar! - b.bar!);

/** A rota atual pertence a esta aba? (`/receitas/12` pertence a `/receitas`) */
export function isActive(path: string, href: string): boolean {
  return path === href || path.startsWith(`${href}/`);
}

/** Seção que contém a rota atual (a que deve abrir sozinha). */
export function sectionOf(path: string): string | undefined {
  return NAV_SECTIONS.find((s) => s.items.some((i) => isActive(path, i.href)))?.id;
}
