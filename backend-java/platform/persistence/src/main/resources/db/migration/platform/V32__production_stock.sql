-- Controle de produção: unidades prontas por receita (estoque de produção) e o histórico de cada produção, com os
-- ingredientes que ela descontou (para o "desfazer" devolver exatamente o que saiu). Aditiva: nada existente muda.
CREATE TABLE IF NOT EXISTS recipe_stock (
  recipe_id  INT           PRIMARY KEY REFERENCES recipes(id) ON DELETE CASCADE,
  user_id    INT           NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  qty        NUMERIC(12,3) NOT NULL DEFAULT 0 CHECK (qty >= 0),
  updated_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS recipe_stock_user_idx ON recipe_stock (user_id);

CREATE TABLE IF NOT EXISTS production_runs (
  id         SERIAL        PRIMARY KEY,
  user_id    INT           NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipe_id  INT           REFERENCES recipes(id) ON DELETE SET NULL,
  recipe     TEXT          NOT NULL,                       -- nome da receita na hora (a receita pode mudar ou sumir)
  qty        NUMERIC(12,3) NOT NULL CHECK (qty > 0),
  total_cost NUMERIC(12,2) NOT NULL DEFAULT 0,
  consumed   JSONB         NOT NULL DEFAULT '[]',          -- [{ingredient_id, name, unit, needed, deducted, cost}]
  forced     BOOLEAN       NOT NULL DEFAULT false,         -- confirmada mesmo com ingrediente faltando
  created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
  undone_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS production_runs_user_idx ON production_runs (user_id, created_at DESC);
