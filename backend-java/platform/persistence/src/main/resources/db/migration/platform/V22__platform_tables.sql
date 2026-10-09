-- Contadores de limite de tentativas compartilhados entre instâncias (G05, G23).
CREATE TABLE IF NOT EXISTS rate_limits (
  key           TEXT        NOT NULL,
  window_start  TIMESTAMPTZ NOT NULL,
  hits          INTEGER     NOT NULL DEFAULT 0,
  PRIMARY KEY (key, window_start)
);
CREATE INDEX IF NOT EXISTS rate_limits_window_idx ON rate_limits (window_start);

-- Última execução de cada tarefa agendada (G13).
CREATE TABLE IF NOT EXISTS job_runs (
  name         TEXT        PRIMARY KEY,
  last_run_on  DATE        NOT NULL,
  last_ok_at   TIMESTAMPTZ,
  last_error   TEXT
);

-- Tentativas de cobrança: no máximo UMA em andamento por pedido; a chave de idempotência é o próprio id (G03).
CREATE TABLE IF NOT EXISTS payment_attempts (
  id              UUID        PRIMARY KEY,
  shop_order_id   INTEGER     REFERENCES shop_orders(id),
  pay_link_id     INTEGER     REFERENCES order_pay_links(id),
  customer_id     INTEGER     REFERENCES customers(id),
  client_ip       TEXT        NOT NULL,
  method          TEXT        NOT NULL CHECK (method IN ('pix', 'cartao')),
  state           TEXT        NOT NULL CHECK (state IN ('in_flight', 'approved', 'pending', 'rejected', 'error')),
  gateway_id      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS payment_attempts_one_in_flight_order
  ON payment_attempts (shop_order_id) WHERE state = 'in_flight' AND shop_order_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS payment_attempts_one_in_flight_link
  ON payment_attempts (pay_link_id) WHERE state = 'in_flight' AND pay_link_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS payment_attempts_customer_idx ON payment_attempts (customer_id, created_at);
CREATE INDEX IF NOT EXISTS payment_attempts_ip_idx ON payment_attempts (client_ip, created_at);

-- Entregas de webhook já processadas (deduplicação por x-request-id; G18).
CREATE TABLE IF NOT EXISTS webhook_deliveries (
  request_id    TEXT        PRIMARY KEY,
  data_id       TEXT        NOT NULL,
  received_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
