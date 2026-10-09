// Características do cardápio: lista fixa de selos que a confeiteira liga/desliga por receita no editor
// do painel (shared com o site/apps, que só exibem o que está marcado). Fixa de propósito — ver spec.
export type MenuTag = { id: string; label: string };

export const MENU_TAGS: MenuTag[] = [
  { id: 'vegano', label: 'Vegano' },
  { id: 'vegetariano', label: 'Vegetariano' },
  { id: 'sem_gluten', label: 'Sem glúten' },
  { id: 'sem_lactose', label: 'Sem lactose' },
  { id: 'contem_gluten', label: 'Contém glúten' },
  { id: 'contem_leite', label: 'Contém leite' },
  { id: 'contem_ovos', label: 'Contém ovos' },
  { id: 'contem_castanhas', label: 'Contém castanhas' },
  { id: 'contem_amendoim', label: 'Contém amendoim' },
  { id: 'contem_soja', label: 'Contém soja' },
];

export const MENU_TAG_IDS = MENU_TAGS.map((t) => t.id);
export const menuTagLabel = (id: string) => MENU_TAGS.find((t) => t.id === id)?.label ?? id;

/** Filtra para só os ids válidos (ignora o que não existir mais na lista fixa), sem duplicatas. */
export function cleanMenuTags(tags: unknown): string[] {
  if (!Array.isArray(tags)) return [];
  return [...new Set(tags.map(String))].filter((t) => MENU_TAG_IDS.includes(t));
}
