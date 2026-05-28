ALTER TABLE daily_choice DROP CONSTRAINT IF EXISTS chk_daily_choice_estado;
ALTER TABLE daily_choice ADD CONSTRAINT chk_daily_choice_estado
    CHECK (estado IN ('PENDIENTE', 'CONFIRMADO', 'COMANDADO', 'ENTREGADO'));

ALTER TABLE daily_choice ADD COLUMN comandado_at TIMESTAMP;
