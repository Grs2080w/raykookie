-- Editor de cardápio do painel: quais receitas aparecem na loja e quais características (tags fixas,
-- ex. "Sem glúten", "Vegano") aparecem em cada uma. Padrão show_on_menu=true: nada some do cardápio
-- hoje por causa desta migration — a confeiteira escolhe o que tirar, não o que manter.
ALTER TABLE recipes
  ADD COLUMN show_on_menu BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}';
