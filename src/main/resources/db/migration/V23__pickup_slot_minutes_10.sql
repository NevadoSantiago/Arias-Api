-- Unidad 8 (rediseño B2C, decisión de usuario 2026-09-25): el horario de
-- retiro pasa a ofrecerse cada 10 minutos en vez de cada 15 (V20). El
-- backend ahora también rechaza horarios no alineados a este paso (ver
-- PickupSlotService.assertValidPickupTime).
ALTER TABLE restaurant_config ALTER COLUMN pickup_slot_minutes SET DEFAULT 10;
UPDATE restaurant_config SET pickup_slot_minutes = 10;
