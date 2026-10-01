-- Tipo explícito de paquete de créditos: reemplaza la dependencia de los
-- códigos mágicos DAY/WEEK. Aditiva y segura sobre datos existentes:
-- la columna nace nullable, se rellena a partir del código y recién
-- entonces pasa a NOT NULL.
--   INDIVIDUAL: su precio por almuerzo es el de pagar un pedido directo (antes DAY)
--   SUGERIDO:   el paquete que el frontend sugiere a quien compra sueltos (antes WEEK)
--   OTRO:       sin comportamiento especial

ALTER TABLE credit_pack ADD COLUMN pack_type VARCHAR(12);

UPDATE credit_pack
SET pack_type = CASE UPPER(code)
    WHEN 'DAY'  THEN 'INDIVIDUAL'
    WHEN 'WEEK' THEN 'SUGERIDO'
    ELSE 'OTRO'
END;

ALTER TABLE credit_pack ALTER COLUMN pack_type SET NOT NULL;

ALTER TABLE credit_pack
    ADD CONSTRAINT chk_credit_pack_type CHECK (pack_type IN ('INDIVIDUAL', 'SUGERIDO', 'OTRO'));

-- A lo sumo un INDIVIDUAL y un SUGERIDO vivos; los borrados (soft delete) no cuentan.
CREATE UNIQUE INDEX uq_credit_pack_special_type
    ON credit_pack (pack_type)
    WHERE deleted_at IS NULL AND pack_type IN ('INDIVIDUAL', 'SUGERIDO');
