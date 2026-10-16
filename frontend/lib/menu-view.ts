import type { Menu, MenuGroup, MenuItem } from '@shared/types';

/*
 * Só apresentação do cardápio: nada aqui grava no banco. Os nomes e grupos vêm do painel; quando o nome de um grupo não
 * descreve o que há dentro (ex.: brownies dentro de "Bolos"), a vitrine mostra o nome certo e junta grupos que acabam
 * com o mesmo nome. Se o dono arrumar as categorias no painel, nada aqui muda nada.
 */

const norm = (s: string) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
const slug = (s: string) => norm(s).replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

type Kind = 'brownie' | 'cookie' | 'bolo';
const KIND_LABEL: Record<Kind, string> = { brownie: 'Brownies', cookie: 'Cookies', bolo: 'Bolos e tortas' };
const KIND_STEM: Record<Kind, RegExp> = { brownie: /brownie/, cookie: /cookie|biscoito/, bolo: /bolo|torta/ };

/** O que o item é, olhando só o nome (a categoria é justamente o que pode estar fora do lugar). */
function kindOfName(name: string): Kind | null {
  const t = norm(name);
  if (/brownie|\bbits\b/.test(t)) return 'brownie';
  if (/cookie|biscoito/.test(t)) return 'cookie';
  if (/bolo|torta|cupcake/.test(t)) return 'bolo';
  return null;
}

/** Grupos como a vitrine mostra: nomes coerentes com o conteúdo e sem grupos repetidos. */
export function presentGroups(menu: Menu): MenuGroup[] {
  const out: MenuGroup[] = [];
  for (const g of menu.groups) {
    let name = g.name;
    if (!g.items.some((i) => i.combo) && g.items.length) {
      const kinds = new Set(g.items.map((i) => kindOfName(i.name)));
      const [only] = kinds.size === 1 ? [...kinds] : [null];
      if (only && !KIND_STEM[only].test(norm(g.name))) name = KIND_LABEL[only];
    }
    const id = name === g.name ? g.id : slug(name);
    const same = out.find((o) => o.id === id);
    if (same) same.items = [...same.items, ...g.items];
    else out.push({ ...g, id, name, items: [...g.items] });
  }
  return out;
}

const SMALL = new Set(['de', 'da', 'do', 'das', 'dos', 'e', 'com', 'em']);
/** "AMOR DE NUTELLA" → "Amor de Nutella". Nomes que já vêm com minúsculas ficam como estão. */
export function niceName(name: string): string {
  if (name !== name.toUpperCase() || name === name.toLowerCase()) return name;
  return name.toLowerCase().split(' ').map((w, i) => (i > 0 && SMALL.has(w) ? w : w.charAt(0).toUpperCase() + w.slice(1))).join(' ');
}

/** Tom do placeholder quando o item ainda não tem foto: cookie, brownie ou o rosa da casa. */
export function placeholderTone(item: Pick<MenuItem, 'name' | 'combo'>): 'cookie' | 'brownie' | 'casa' {
  if (item.combo) return 'casa';
  const k = kindOfName(item.name);
  return k === 'cookie' ? 'cookie' : k === 'brownie' ? 'brownie' : 'casa';
}
