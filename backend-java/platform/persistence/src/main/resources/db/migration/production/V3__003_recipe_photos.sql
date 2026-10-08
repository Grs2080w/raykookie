-- Foto opcional de cada receita (aparece no cardápio da landing page). Fica em tabela própria para não pesar as listagens.
CREATE TABLE recipe_photos (
  recipe_id    INT PRIMARY KEY REFERENCES recipes(id) ON DELETE CASCADE,
  content_type TEXT NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
  data         BYTEA NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
