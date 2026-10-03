CREATE TABLE users (
  id            SERIAL PRIMARY KEY,
  username      TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_username_key ON users (lower(username));

CREATE TABLE sessions (
  id         SERIAL PRIMARY KEY,
  user_id    INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sessions_user_idx ON sessions (user_id);

CREATE TABLE categories (
  id      SERIAL PRIMARY KEY,
  user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind    TEXT NOT NULL CHECK (kind IN ('ingredient','recipe','expense')),
  name    TEXT NOT NULL,
  icon    TEXT NOT NULL DEFAULT 'tag',
  color   TEXT NOT NULL DEFAULT '#e0357a',
  UNIQUE (user_id, kind, name)
);

CREATE TABLE ingredients (
  id           SERIAL PRIMARY KEY,
  user_id      INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  category_id  INT REFERENCES categories(id) ON DELETE SET NULL,
  name         TEXT NOT NULL,
  unit         TEXT NOT NULL CHECK (unit IN ('g','ml','un')),
  stock_qty    NUMERIC(14,3) NOT NULL DEFAULT 0,   -- em embalagens
  package_size NUMERIC(14,3) NOT NULL CHECK (package_size > 0), -- g/ml/un por embalagem
  package_cost NUMERIC(14,2) NOT NULL DEFAULT 0,
  min_stock    NUMERIC(14,3) NOT NULL DEFAULT 0,   -- em embalagens
  supplier     TEXT NOT NULL DEFAULT '',
  notes        TEXT NOT NULL DEFAULT '',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ingredients_user_idx ON ingredients (user_id);

CREATE TABLE recipes (
  id          SERIAL PRIMARY KEY,
  user_id     INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  category_id INT REFERENCES categories(id) ON DELETE SET NULL,
  name        TEXT NOT NULL,
  yield_units NUMERIC(14,3) NOT NULL DEFAULT 1 CHECK (yield_units > 0),
  margin_pct  NUMERIC(6,2) NOT NULL DEFAULT 100 CHECK (margin_pct >= 0),
  prep_hours  NUMERIC(8,2) NOT NULL DEFAULT 0,
  notes       TEXT NOT NULL DEFAULT '',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX recipes_user_idx ON recipes (user_id);

CREATE TABLE recipe_items (
  id            SERIAL PRIMARY KEY,
  recipe_id     INT NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
  ingredient_id INT NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
  qty           NUMERIC(14,3) NOT NULL CHECK (qty >= 0)
);
CREATE INDEX recipe_items_recipe_idx ON recipe_items (recipe_id);

CREATE TABLE settings (
  user_id               INT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  hourly_rate           NUMERIC(10,2) NOT NULL DEFAULT 0,
  monthly_units         NUMERIC(10,2) NOT NULL DEFAULT 100 CHECK (monthly_units > 0)
);

CREATE TABLE fixed_costs (
  id          SERIAL PRIMARY KEY,
  user_id     INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  category_id INT REFERENCES categories(id) ON DELETE SET NULL,
  name        TEXT NOT NULL,
  kind        TEXT NOT NULL DEFAULT 'fixed' CHECK (kind IN ('fixed','variable')),
  amount      NUMERIC(12,2) NOT NULL DEFAULT 0
);
CREATE INDEX fixed_costs_user_idx ON fixed_costs (user_id);

CREATE TABLE orders (
  id            SERIAL PRIMARY KEY,
  user_id       INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  number        INT NOT NULL,
  order_date    DATE NOT NULL DEFAULT CURRENT_DATE,
  customer      TEXT NOT NULL,
  recipe_id     INT REFERENCES recipes(id) ON DELETE SET NULL,
  product       TEXT NOT NULL,
  qty           NUMERIC(12,3) NOT NULL DEFAULT 1 CHECK (qty > 0),
  unit_price    NUMERIC(12,2) NOT NULL DEFAULT 0,
  delivery_date DATE,
  status        TEXT NOT NULL DEFAULT 'novo' CHECK (status IN ('novo','producao','pronto','entregue','cancelado')),
  payment       TEXT NOT NULL DEFAULT 'pendente' CHECK (payment IN ('pendente','sinal','pago')),
  notes         TEXT NOT NULL DEFAULT '',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, number)
);
CREATE INDEX orders_user_date_idx ON orders (user_id, order_date);

CREATE TABLE goals (
  user_id       INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  month         DATE NOT NULL,  -- primeiro dia do mês
  revenue       NUMERIC(12,2) NOT NULL DEFAULT 0,
  orders        INT NOT NULL DEFAULT 0,
  new_customers INT NOT NULL DEFAULT 0,
  avg_ticket    NUMERIC(12,2) NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, month)
);
