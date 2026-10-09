-- Feedback dos clientes (nota de 1 a 5, texto e fotos opcionais), enviado pelo site/apps e lido pela confeiteira
-- no painel. Mesmo padrão de refunds/refund_photos: fotos em tabela à parte, para a lista nunca carregar os bytes.
CREATE TABLE feedback (
  id          SERIAL PRIMARY KEY,
  owner_id    INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,       -- confeiteira que recebe (SHOP_OWNER)
  customer_id INT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
  rating      SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
  message     TEXT NOT NULL DEFAULT '',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX feedback_owner_idx ON feedback (owner_id, created_at DESC);

CREATE TABLE feedback_photos (
  id           SERIAL PRIMARY KEY,
  feedback_id  INT NOT NULL REFERENCES feedback(id) ON DELETE CASCADE,
  idx          SMALLINT NOT NULL CHECK (idx BETWEEN 1 AND 5),
  content_type TEXT NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
  data         BYTEA NOT NULL,
  size         INT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (feedback_id, idx)
);
