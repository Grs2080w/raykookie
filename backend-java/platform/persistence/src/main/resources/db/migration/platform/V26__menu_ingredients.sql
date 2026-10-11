-- Página do Cardápio: por produto, se a landing page mostra a lista de ingredientes da receita padrão. Aditiva.
ALTER TABLE recipes ADD COLUMN IF NOT EXISTS show_ingredients BOOLEAN NOT NULL DEFAULT false;
