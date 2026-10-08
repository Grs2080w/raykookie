-- Início da última tentativa de cada tarefa agendada (G13): evita que duas instâncias repitam a mesma
-- tarefa no mesmo ciclo de 5 minutos e espaça as novas tentativas depois de uma falha. Aditiva.
ALTER TABLE job_runs ADD COLUMN IF NOT EXISTS last_started_at TIMESTAMPTZ;
