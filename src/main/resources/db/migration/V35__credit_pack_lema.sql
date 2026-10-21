-- Lema opcional del paquete (texto corto que acompaña a la tarjeta).
-- Aditiva: la columna nace nullable y se rellena por tipo solo donde está vacía.
ALTER TABLE credit_pack ADD COLUMN lema VARCHAR(60);

UPDATE credit_pack SET lema = 'Resolvé hoy'       WHERE pack_type = 'INDIVIDUAL' AND lema IS NULL;
UPDATE credit_pack SET lema = 'Resolvé tu semana' WHERE pack_type = 'SUGERIDO'   AND lema IS NULL;
UPDATE credit_pack SET lema = 'Resolvé el mes'    WHERE pack_type = 'OTRO'       AND lema IS NULL;
