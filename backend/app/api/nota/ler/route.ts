import { fail, ok, route, str } from '@/lib/api';
import { q, q1 } from '@/lib/db';
import { isNfceUrl, matchIngredient, nameTokens, parseNfceHtml, planAdd, planCreate } from '@/lib/nfce';

// O app baixa a página da nota (o link do QR code) e manda o HTML aqui; o servidor não acessa o portal da SEFAZ.
export const POST = route(async (req, user) => {
  const b = await req.json();
  const url = str(b.url).slice(0, 2000);
  const html = typeof b.html === 'string' ? b.html : '';
  if (url && !isNfceUrl(url)) return fail('Esse QR code não é de uma nota fiscal (o link precisa ser de um portal .gov.br).');
  if (html.length < 200) return fail('Não consegui abrir a página da nota. Confira a internet e tente de novo.');
  if (html.length > 3_000_000) return fail('A página da nota veio grande demais para ler.');

  const nota = parseNfceHtml(html, url);
  if (!nota) return fail('Não consegui ler os itens dessa nota. O portal pode estar pedindo um código de verificação (captcha) ou ter um formato que ainda não conheço. Tente de novo mais tarde ou cadastre os itens à mão.', 422);

  const ingredients = await q('SELECT id, name, unit, package_size, package_cost FROM ingredients WHERE user_id=$1 ORDER BY lower(name)', [user.id]);
  const aliases = new Map((await q('SELECT item_key, ingredient_id FROM receipt_aliases WHERE user_id=$1', [user.id])).map((r) => [r.item_key, r.ingredient_id as number]));
  const done = await q1('SELECT created_at FROM receipt_imports WHERE user_id=$1 AND access_key=$2', [user.id, nota.key]);

  const items = nota.items.map((it, index) => {
    const aliased = aliases.get(nameTokens(it.name).join(' '));
    const ing = aliased ? ingredients.find((i) => i.id === aliased) : undefined;
    const m = ing ? { ingredient_id: ing.id, name: ing.name, exact: true } : matchIngredient(it.name, ingredients);
    const target = m ? ingredients.find((i) => i.id === m.ingredient_id) : undefined;
    return {
      index, ...it, match: m, // exact: soma direto; senão é sugestão que o app pergunta
      add: m && target ? planAdd(it, target) : null,
      create: planCreate(it),
    };
  });
  return ok({ key: nota.key, store: nota.store, date: nota.date, total: nota.total, already_imported: done?.created_at ?? null, items });
});
