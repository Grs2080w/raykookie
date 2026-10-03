import { ok } from '@/lib/api';
import { getMenu } from '@/lib/shop-menu';

// Cardápio público da loja (receitas do painel: só nome, preço e foto).
export async function GET() { return ok(await getMenu()); }
