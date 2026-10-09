import { num, str } from './api';

export function parseIngredient(b: any) {
  const name = str(b.name);
  const unit = str(b.unit);
  const packageSize = num(b.package_size);
  if (!name) return { error: 'Informe o nome do ingrediente.' };
  if (!['g', 'ml', 'un'].includes(unit)) return { error: 'Escolha a unidade (g, ml ou un).' };
  if (packageSize <= 0) return { error: 'O tamanho da embalagem precisa ser maior que zero.' };
  return {
    value: [
      b.category_id ? Number(b.category_id) : null, name.slice(0, 80), unit,
      Math.max(0, num(b.stock_qty)), packageSize, Math.max(0, num(b.package_cost)),
      Math.max(0, num(b.min_stock)), str(b.supplier).slice(0, 80), str(b.notes).slice(0, 300),
    ],
  };
}

