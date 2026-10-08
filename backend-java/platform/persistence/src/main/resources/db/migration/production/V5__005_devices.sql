-- Celulares do app de gestão que recebem push (Firebase Cloud Messaging) quando chega um pedido novo.
CREATE TABLE device_tokens (
  id          SERIAL PRIMARY KEY,
  user_id     INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token       TEXT NOT NULL UNIQUE,            -- token FCM do aparelho (um aparelho só pode pertencer a uma conta)
  platform    TEXT NOT NULL DEFAULT 'android',
  sound       TEXT NOT NULL DEFAULT 'sino',    -- som escolhido no app (define o canal de notificação)
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX device_tokens_user_idx ON device_tokens(user_id);
