-- Várias versões de receita por produto (Receita 1, Receita 2…), com uma padrão. A padrão é a que vale para o
-- preço do cardápio, combos, lucro e metas: os campos yield_units/prep_hours de recipes e a tabela recipe_items
-- continuam sendo a cópia dela (quem só lê recipes/recipe_items segue certo). Aditiva.
CREATE TABLE IF NOT EXISTS recipe_variants (
  id           SERIAL        PRIMARY KEY,
  recipe_id    INT           NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
  name         TEXT          NOT NULL,
  position     INT           NOT NULL DEFAULT 0,
  yield_units  NUMERIC(14,3) NOT NULL DEFAULT 1 CHECK (yield_units > 0),
  prep_hours   NUMERIC(8,2)  NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS recipe_variants_recipe_idx ON recipe_variants (recipe_id);

CREATE TABLE IF NOT EXISTS recipe_variant_items (
  id            SERIAL        PRIMARY KEY,
  variant_id    INT           NOT NULL REFERENCES recipe_variants(id) ON DELETE CASCADE,
  ingredient_id INT           NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
  qty           NUMERIC(14,3) NOT NULL CHECK (qty >= 0)
);
CREATE INDEX IF NOT EXISTS recipe_variant_items_variant_idx ON recipe_variant_items (variant_id);

ALTER TABLE recipes ADD COLUMN IF NOT EXISTS default_variant_id INT REFERENCES recipe_variants(id) ON DELETE SET NULL;

-- Cada receita existente vira a "Receita 1" padrão, com os mesmos ingredientes: nenhum preço muda.
INSERT INTO recipe_variants (recipe_id, name, position, yield_units, prep_hours)
SELECT r.id, 'Receita 1', 0, r.yield_units, r.prep_hours FROM recipes r
 WHERE NOT EXISTS (SELECT 1 FROM recipe_variants v WHERE v.recipe_id = r.id);
UPDATE recipes r SET default_variant_id = v.id
  FROM recipe_variants v
 WHERE v.recipe_id = r.id AND v.position = 0 AND r.default_variant_id IS NULL;
INSERT INTO recipe_variant_items (variant_id, ingredient_id, qty)
SELECT r.default_variant_id, ri.ingredient_id, ri.qty
  FROM recipe_items ri JOIN recipes r ON r.id = ri.recipe_id
 WHERE NOT EXISTS (SELECT 1 FROM recipe_variant_items vi WHERE vi.variant_id = r.default_variant_id)
 ORDER BY ri.id;
