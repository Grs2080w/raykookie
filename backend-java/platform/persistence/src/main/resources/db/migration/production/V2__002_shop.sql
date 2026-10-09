-- Loja online: contas de clientes (separadas dos usuários do painel) e pedidos feitos pelo site.
CREATE TABLE customers (
  id            SERIAL PRIMARY KEY,
  email         TEXT NOT NULL,
  name          TEXT NOT NULL,
  phone         TEXT NOT NULL DEFAULT '',
  password_hash TEXT,                      -- nulo quando a conta só usa o Google
  google_sub    TEXT UNIQUE,
  cep           TEXT NOT NULL DEFAULT '',
  street        TEXT NOT NULL DEFAULT '',
  number        TEXT NOT NULL DEFAULT '',
  complement    TEXT NOT NULL DEFAULT '',
  district      TEXT NOT NULL DEFAULT '',
  city          TEXT NOT NULL DEFAULT '',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX customers_email_key ON customers (lower(email));

CREATE TABLE customer_sessions (
  token_hash  TEXT PRIMARY KEY,
  customer_id INT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
  expires_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE shop_orders (
  id             SERIAL PRIMARY KEY,
  customer_id    INT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
  owner_id       INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,  -- painel que recebe o pedido
  contact_name   TEXT NOT NULL,
  phone          TEXT NOT NULL,
  fulfillment    TEXT NOT NULL CHECK (fulfillment IN ('retirada','entrega')),
  cep            TEXT NOT NULL DEFAULT '',
  street         TEXT NOT NULL DEFAULT '',
  number         TEXT NOT NULL DEFAULT '',
  complement     TEXT NOT NULL DEFAULT '',
  district       TEXT NOT NULL DEFAULT '',
  city           TEXT NOT NULL DEFAULT '',
  scheduled_for  DATE NOT NULL,
  time_window    TEXT NOT NULL CHECK (time_window IN ('manha','tarde','noite')),
  payment_method TEXT NOT NULL CHECK (payment_method IN ('pix','cartao','dinheiro')),
  change_for     NUMERIC(10,2),
  notes          TEXT NOT NULL DEFAULT '',
  items          JSONB NOT NULL,           -- [{id, name, qty, unit_price}] como foi pedido
  total          NUMERIC(12,2) NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX shop_orders_customer_idx ON shop_orders (customer_id, created_at DESC);

-- cada item do pedido online vira uma linha em `orders`, para aparecer na tela Pedidos do painel
ALTER TABLE orders ADD COLUMN shop_order_id INT REFERENCES shop_orders(id) ON DELETE SET NULL;
CREATE INDEX orders_shop_idx ON orders (shop_order_id);
