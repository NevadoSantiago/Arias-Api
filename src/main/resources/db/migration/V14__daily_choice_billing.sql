-- Facturación: congela el precio acordado en el pedido mismo.
--
-- Por qué: hasta ahora daily_choice solo guardaba `dish_categoria` como STRING.
-- Calcular la factura joineando contra company_category_price ACTUAL significa
-- que renegociar una tarifa reescribe facturas ya emitidas, y que renombrar una
-- categoría deja los pedidos históricos huérfanos silenciosamente.
--
-- Ambas columnas son NULLABLE a propósito: los pedidos anteriores a esta
-- migración no tienen precio acordado recuperable de forma confiable.
-- El reporte los trata como no facturables (ver BillingService).
--
-- Migración ADITIVA: no borra ni modifica datos existentes.
ALTER TABLE daily_choice ADD COLUMN category_id BIGINT REFERENCES category(id);
ALTER TABLE daily_choice ADD COLUMN precio_snapshot INTEGER;

-- Backfill best-effort de los pedidos ya existentes. El único vínculo disponible
-- es el nombre de la categoría (el string snapshot), justamente el punto débil
-- que esta migración viene a cerrar hacia adelante.
UPDATE daily_choice d
SET category_id = c.id
FROM category c
WHERE d.category_id IS NULL
  AND c.nombre = d.dish_categoria;

UPDATE daily_choice d
SET precio_snapshot = p.precio
FROM company_category_price p
WHERE d.precio_snapshot IS NULL
  AND d.category_id IS NOT NULL
  AND p.company_id = d.company_id
  AND p.category_id = d.category_id;

-- El reporte de facturación filtra por confirmed_at IS NOT NULL sobre un rango
-- de fechas. Índice parcial: solo indexa las filas que el reporte mira.
CREATE INDEX idx_daily_choice_billing
    ON daily_choice (fecha, company_id)
    WHERE confirmed_at IS NOT NULL;
