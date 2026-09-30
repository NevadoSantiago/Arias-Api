-- Unidad 8: configuración de la programación de retiro B2C. Un solo valor de
-- tiempo de preparación (pickup_lead_minutes) cubre los dos usos que pedía el
-- negocio: retiro más temprano ofrecido (now + lead) y punto de consumo
-- automático (pickup_at - lead) — diseño §"pickup_lead_minutes: un solo
-- valor, dos usos". credit_expiry_days también se vuelve configurable acá,
-- reemplazando el default fijo de CreditLedgerService (unidad 3). Los tres
-- campos restantes (pickup_slot_minutes, daily_summary_time,
-- pickup_reminder_minutes) se agregan en esta misma migración porque el
-- diseño los reserva todos para V20, aunque solo pickup_slot_minutes se usa
-- ya en esta unidad — los otros dos quedan listos para la unidad 12
-- (notificaciones).
ALTER TABLE restaurant_config ADD COLUMN pickup_lead_minutes     INTEGER NOT NULL DEFAULT 20;
ALTER TABLE restaurant_config ADD COLUMN credit_expiry_days      INTEGER NOT NULL DEFAULT 90;
ALTER TABLE restaurant_config ADD COLUMN pickup_window_start     TIME    NOT NULL DEFAULT '11:00';
ALTER TABLE restaurant_config ADD COLUMN pickup_window_end       TIME    NOT NULL DEFAULT '15:00';
ALTER TABLE restaurant_config ADD COLUMN pickup_slot_minutes     INTEGER NOT NULL DEFAULT 15;
ALTER TABLE restaurant_config ADD COLUMN daily_summary_time      TIME    NOT NULL DEFAULT '08:00';
ALTER TABLE restaurant_config ADD COLUMN pickup_reminder_minutes INTEGER NOT NULL DEFAULT 25;
