CREATE TABLE dish_calendario (
    dish_id BIGINT NOT NULL REFERENCES dish(id) ON DELETE CASCADE,
    fecha   DATE NOT NULL,
    PRIMARY KEY (dish_id, fecha)
);

CREATE INDEX idx_dish_calendario_fecha ON dish_calendario(fecha);

INSERT INTO dish_calendario (dish_id, fecha)
SELECT DISTINCT
    dds.dish_id,
    gs::DATE AS fecha
FROM dish_dia_semana dds
JOIN dish d ON d.id = dds.dish_id
CROSS JOIN generate_series(CURRENT_DATE, CURRENT_DATE + INTERVAL '28 days', INTERVAL '1 day') AS gs
WHERE d.especial = true
  AND d.enabled = true
  AND CASE EXTRACT(DOW FROM gs)
        WHEN 1 THEN 'LUNES'
        WHEN 2 THEN 'MARTES'
        WHEN 3 THEN 'MIERCOLES'
        WHEN 4 THEN 'JUEVES'
        WHEN 5 THEN 'VIERNES'
        WHEN 6 THEN 'SABADO'
        WHEN 0 THEN 'DOMINGO'
      END = dds.dia
ON CONFLICT DO NOTHING;

DROP TABLE dish_dia_semana;
