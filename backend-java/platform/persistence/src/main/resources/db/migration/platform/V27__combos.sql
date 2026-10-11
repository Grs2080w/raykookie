-- Combos do cardápio: N produtos com quantidade e um preço (nulo = o sugerido: soma dos itens menos o desconto de
-- combo, nunca abaixo do custo + margem mínima). Uma linha de pedido de combo leva combo_id (recipe_id nulo).
-- Aditiva.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS combo_discount_pct NUMERIC(5,2) NOT NULL DEFAULT 10
  CHECK (combo_discount_pct >= 0 AND combo_discount_pct < 100);

CREATE TABLE IF NOT EXISTS combos (
  id            SERIAL        PRIMARY KEY,
  user_id       INT           NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name          TEXT          NOT NULL,
  price         NUMERIC(12,2) CHECK (price IS NULL OR price > 0),
  show_on_menu  BOOLEAN       NOT NULL DEFAULT true,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS combos_user_idx ON combos (user_id);

CREATE TABLE IF NOT EXISTS combo_items (
  id         SERIAL  PRIMARY KEY,
  combo_id   INT     NOT NULL REFERENCES combos(id) ON DELETE CASCADE,
  recipe_id  INT     NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
  qty        INT     NOT NULL CHECK (qty > 0 AND qty <= 99)
);
CREATE INDEX IF NOT EXISTS combo_items_combo_idx ON combo_items (combo_id);

ALTER TABLE orders ADD COLUMN IF NOT EXISTS combo_id INT REFERENCES combos(id) ON DELETE SET NULL;
