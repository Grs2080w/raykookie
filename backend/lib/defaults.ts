import type { CatKind } from '@shared/types';
export type { CatKind };
type Seed = [name: string, icon: string, color: string];

export const DEFAULT_CATEGORIES: Record<CatKind, Seed[]> = {
  ingredient: [
    ['Farinhas e amidos', 'wheat', '#d9a441'],
    ['Açúcares e adoçantes', 'candy', '#e0357a'],
    ['Laticínios', 'milk', '#5b8def'],
    ['Chocolates e cacau', 'cookie', '#8a5a44'],
    ['Ovos', 'egg', '#f0a04b'],
    ['Gorduras', 'droplet', '#e6b422'],
    ['Frutas', 'apple', '#e5484d'],
    ['Castanhas e sementes', 'nut', '#a0713a'],
    ['Recheios e coberturas', 'cake-slice', '#c2438a'],
    ['Confeitos e decoração', 'sparkles', '#9b6cf0'],
    ['Essências e corantes', 'flask-conical', '#2fa4a9'],
    ['Fermentos e aditivos', 'wind', '#7a8899'],
    ['Embalagens', 'package', '#5f9d6e'],
    ['Outros', 'tag', '#8c8c99'],
  ],
  recipe: [
    ['Bolos', 'cake', '#e0357a'],
    ['Bolos no pote', 'cup-soda', '#c2438a'],
    ['Brigadeiros e docinhos', 'candy', '#8a5a44'],
    ['Cookies e biscoitos', 'cookie', '#d9a441'],
    ['Tortas', 'pie-chart', '#e5484d'],
    ['Cupcakes', 'cake-slice', '#9b6cf0'],
    ['Sobremesas', 'ice-cream-cone', '#5b8def'],
    ['Salgados', 'sandwich', '#f0a04b'],
    ['Pães', 'croissant', '#a0713a'],
    ['Bebidas', 'coffee', '#2fa4a9'],
    ['Kits e presentes', 'gift', '#5f9d6e'],
    ['Outros', 'tag', '#8c8c99'],
  ],
  expense: [
    ['Energia', 'zap', '#e6b422'],
    ['Água', 'droplets', '#5b8def'],
    ['Gás', 'flame', '#f0a04b'],
    ['Aluguel', 'home', '#7a8899'],
    ['Internet e telefone', 'wifi', '#2fa4a9'],
    ['Transporte e entrega', 'truck', '#5f9d6e'],
    ['Marketing', 'megaphone', '#e0357a'],
    ['Impostos e taxas', 'receipt', '#e5484d'],
    ['Equipamentos', 'wrench', '#9b6cf0'],
    ['Outros', 'tag', '#8c8c99'],
  ],
};
