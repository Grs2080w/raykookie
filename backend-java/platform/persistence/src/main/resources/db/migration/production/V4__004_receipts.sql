-- Leitura de notas fiscais (NFC-e) pelo app de gestão.
-- Cada nota só pode ser lançada uma vez por conta (evita somar o mesmo cupom duas vezes no estoque).
CREATE TABLE receipt_imports (
  id          SERIAL PRIMARY KEY,
  user_id     INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  access_key  TEXT NOT NULL,                 -- chave de 44 dígitos da nota (ou "h:" + impressão digital do conteúdo)
  store       TEXT NOT NULL DEFAULT '',
  issued_on   DATE,
  total       NUMERIC(14,2),
  added       INT NOT NULL DEFAULT 0,        -- itens somados a ingredientes que já existiam
  created     INT NOT NULL DEFAULT 0,        -- ingredientes novos criados a partir da nota
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, access_key)
);

-- "Apelidos": depois que a confeiteira confirma que "AÇUCAR REFINADO UNIAO" é o ingrediente "Açúcar", o app lembra.
CREATE TABLE receipt_aliases (
  user_id       INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  item_key      TEXT NOT NULL,               -- palavras normalizadas do nome na nota
  ingredient_id INT NOT NULL REFERENCES ingredients(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, item_key)
);
