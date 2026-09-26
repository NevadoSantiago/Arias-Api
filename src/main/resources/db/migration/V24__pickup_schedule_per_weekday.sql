-- Unidad 8 (rediseño B2C, decisión de usuario 2026-09-26 — B5/F14): franja
-- horaria de retiro configurable por día de la semana, con una sola franja
-- por día (apertura-cierre) y la posibilidad de cerrar el local ese día.
--
-- `restaurant_config.pickup_window_start/end` (V20) quedan SUPERSEDED por
-- esta tabla: PickupSlotService ya no las usa para calcular slots ni validar
-- horarios (ver PickupSlotService.assertValidPickupTime / slotsFor). Se
-- conservan sin eliminar por compatibilidad hacia atrás de
-- RestaurantConfigDto (campos deprecated, siguen editables vía
-- PUT /api/v1/restaurant-config pero ya no afectan la franja real).
CREATE TABLE pickup_schedule (
    -- ISO: 1=lunes .. 7=domingo (java.time.DayOfWeek#getValue()).
    day_of_week  INTEGER NOT NULL PRIMARY KEY CHECK (day_of_week BETWEEN 1 AND 7),
    open         BOOLEAN NOT NULL,
    window_start TIME,
    window_end   TIME,
    CONSTRAINT chk_pickup_schedule_window CHECK (
        NOT open OR (window_start IS NOT NULL AND window_end IS NOT NULL AND window_end > window_start)
    )
);

-- Semilla: los 7 días abiertos con la ventana global vigente de
-- restaurant_config, para que el comportamiento no cambie al migrar.
INSERT INTO pickup_schedule (day_of_week, open, window_start, window_end)
SELECT d, TRUE, rc.pickup_window_start, rc.pickup_window_end
FROM generate_series(1, 7) AS d
CROSS JOIN (SELECT pickup_window_start, pickup_window_end FROM restaurant_config WHERE id = 1) AS rc;

COMMENT ON COLUMN restaurant_config.pickup_window_start IS 'Superseded by pickup_schedule (V24) — kept for backward compatibility only.';
COMMENT ON COLUMN restaurant_config.pickup_window_end IS 'Superseded by pickup_schedule (V24) — kept for backward compatibility only.';
