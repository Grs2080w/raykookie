-- Gramatura de cada unidade que a receita rende (g). 0 = não informada.
ALTER TABLE recipes ADD COLUMN unit_weight_g NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (unit_weight_g >= 0);
