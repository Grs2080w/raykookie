-- Endereços de retirada: a confeiteira guarda os que mais usa e escolhe qual vale agora (só um fica ativo).
CREATE TABLE pickup_addresses (
  id           SERIAL PRIMARY KEY,
  user_id      INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  label        TEXT NOT NULL,                       -- apelido, ex.: "Ateliê", "Casa da Rayka"
  cep          TEXT NOT NULL DEFAULT '',
  street       TEXT NOT NULL,
  number       TEXT NOT NULL,
  complement   TEXT NOT NULL DEFAULT '',
  district     TEXT NOT NULL,
  city         TEXT NOT NULL,
  instructions TEXT NOT NULL DEFAULT '',            -- como chegar / onde tocar, ex.: "Portão azul, 2º andar"
  is_active    BOOLEAN NOT NULL DEFAULT false,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX pickup_one_active_idx ON pickup_addresses (user_id) WHERE is_active;
CREATE INDEX pickup_user_idx ON pickup_addresses (user_id);

-- o pedido guarda o endereço de retirada da época: trocar o endereço depois não muda pedidos já feitos
ALTER TABLE shop_orders ADD COLUMN pickup_address TEXT NOT NULL DEFAULT '';
