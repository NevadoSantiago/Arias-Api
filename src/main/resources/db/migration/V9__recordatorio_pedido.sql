-- Opt-in (por default) al recordatorio diario por mail si no hay pedido para hoy.
ALTER TABLE users
    ADD COLUMN recibe_recordatorio_pedido BOOLEAN NOT NULL DEFAULT TRUE;

-- Log de envíos del cron. Una fila por fecha — se usa como dedup para que
-- el job no mande mails dos veces el mismo día (si el server reinicia o el
-- cron se solapa).
CREATE TABLE reminder_run_log (
    fecha      DATE NOT NULL PRIMARY KEY,
    sent_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recipients INT NOT NULL
);
