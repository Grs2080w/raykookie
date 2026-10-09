-- Pesquisa de preços (sites de fornecedores e cotações manuais), histórico de preços, preço do cardápio fixado e alertas de margem.
ALTER TABLE ingredients ADD COLUMN price_updated_at TIMESTAMPTZ;
ALTER TABLE recipes ADD COLUMN sale_price NUMERIC(12,2) CHECK (sale_price IS NULL OR sale_price > 0);   -- preço do cardápio fixado; nulo = o sugerido pelo cálculo
ALTER TABLE settings ADD COLUMN min_margin_pct NUMERIC(6,2) NOT NULL DEFAULT 30 CHECK (min_margin_pct >= 0);

CREATE TABLE suppliers (
  id          SERIAL PRIMARY KEY,
  user_id     INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  kind        TEXT NOT NULL CHECK (kind IN ('site','loja')),
  url         TEXT NOT NULL DEFAULT '',           -- site: endereço da loja virtual
  address     TEXT NOT NULL DEFAULT '',           -- loja física
  notes       TEXT NOT NULL DEFAULT '',
  scraper     TEXT CHECK (scraper IN ('tray')),   -- plataforma reconhecida; nulo = só cotação manual
  last_ok_at  TIMESTAMPTZ,
  last_error  TEXT NOT NULL DEFAULT '',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX suppliers_user_idx ON suppliers (user_id);

-- Produto de um site de fornecedor, ligado (ou não) a um ingrediente. Os ligados são atualizados todo dia.
CREATE TABLE supplier_products (
  id            SERIAL PRIMARY KEY,
  supplier_id   INT NOT NULL REFERENCES suppliers(id) ON DELETE CASCADE,
  ingredient_id INT REFERENCES ingredients(id) ON DELETE SET NULL,
  external_url  TEXT NOT NULL,
  name          TEXT NOT NULL,
  pack_size     NUMERIC(14,3) NOT NULL DEFAULT 0,
  pack_unit     TEXT NOT NULL DEFAULT 'un' CHECK (pack_unit IN ('g','ml','un')),
  price         NUMERIC(12,2) NOT NULL DEFAULT 0,
  available     BOOLEAN NOT NULL DEFAULT true,
  seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (supplier_id, external_url)
);
CREATE INDEX supplier_products_ing_idx ON supplier_products (ingredient_id);

-- Histórico de preços de cada ingrediente: de fornecedores, cotações manuais, notas fiscais e edições do próprio preço.
CREATE TABLE price_quotes (
  id                  BIGSERIAL PRIMARY KEY,
  user_id             INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  ingredient_id       INT NOT NULL REFERENCES ingredients(id) ON DELETE CASCADE,
  supplier_id         INT REFERENCES suppliers(id) ON DELETE SET NULL,
  supplier_product_id INT REFERENCES supplier_products(id) ON DELETE SET NULL,
  price               NUMERIC(12,2) NOT NULL CHECK (price >= 0),
  pack_size           NUMERIC(14,3) NOT NULL CHECK (pack_size > 0),
  pack_unit           TEXT NOT NULL CHECK (pack_unit IN ('g','ml','un')),
  source              TEXT NOT NULL CHECK (source IN ('scraper','manual','nota','edicao')),
  at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX price_quotes_ing_idx ON price_quotes (ingredient_id, at DESC);

CREATE TABLE price_alerts (
  id            SERIAL PRIMARY KEY,
  user_id       INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipe_id     INT NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
  ingredient_id INT REFERENCES ingredients(id) ON DELETE SET NULL,
  kind          TEXT NOT NULL CHECK (kind IN ('margem','preco')),     -- margem: caiu (preço fixado); preco: o sugerido subiu
  before_value  NUMERIC(12,2) NOT NULL,                               -- margem % ou preço, antes
  after_value   NUMERIC(12,2) NOT NULL,
  suggested     NUMERIC(12,2),                                        -- preço sugerido novo (para "Aplicar")
  cause         TEXT NOT NULL DEFAULT '',
  read          BOOLEAN NOT NULL DEFAULT false,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX price_alerts_user_idx ON price_alerts (user_id, read, created_at DESC);
