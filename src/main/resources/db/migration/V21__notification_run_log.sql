-- Unidad 12: notificaciones del ciclo del pedido (resumen matutino, alerta
-- de cancelación, recordatorio de retiro).

-- Dedup atómico del resumen matutino: una fila por (tipo, fecha) — mismo
-- patrón de PK que reminder_run_log. Si el server reinicia o el cron se
-- solapa, el segundo intento choca contra la PK y no reenvía.
CREATE TABLE notification_run_log (
    tipo       VARCHAR(30) NOT NULL,
    fecha      DATE        NOT NULL,
    sent_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recipients INT         NOT NULL,
    PRIMARY KEY (tipo, fecha)
);

-- Dedup del recordatorio de retiro: a diferencia del resumen (una vez por
-- día), el recordatorio es por pedido individual — varios pedidos comparten
-- el mismo día con horarios de retiro distintos, así que (tipo, fecha) no
-- alcanza. El dedup atómico vive en el propio pedido (claim vía
-- UPDATE ... WHERE reminder_sent_at IS NULL, mismo patrón que el decremento
-- de stock en dish.stock_actual).
ALTER TABLE orders ADD COLUMN reminder_sent_at TIMESTAMP NULL;
