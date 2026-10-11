-- Último build de cada app já avisado aos aparelhos (aviso de versão nova pelo push). A primeira leitura só
-- registra o build atual, sem avisar ninguém. Aditiva.
CREATE TABLE IF NOT EXISTS release_notices (
  app          TEXT        PRIMARY KEY CHECK (app IN ('client', 'server')),
  build        INTEGER     NOT NULL,
  notified_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
