-- El único "tiempo de preparación" (pickup_lead_minutes) se separa en tres
-- parámetros independientes, configurables desde el panel de administración:
--   * pickup_lead_minutes (existente): tiempo de preparación. Solo lado
--     administrativo: define cuándo un pedido programado pasa solo a
--     CONFIRMADO (consume créditos) y la cocina lo ve.
--   * order_lead_minutes (nuevo): "lo antes posible". Mínimo de anticipación
--     para pedir; define el primer horario de retiro ofrecido.
--   * cancel_limit_minutes (nuevo): hasta cuántos minutos antes del retiro el
--     cliente puede cancelar o cambiar el pedido.
-- Ambas columnas nuevas se inicializan con el valor actual de la preparación
-- para que el comportamiento no cambie hasta que el admin las edite.
ALTER TABLE restaurant_config
    ADD COLUMN order_lead_minutes INTEGER NOT NULL DEFAULT 20,
    ADD COLUMN cancel_limit_minutes INTEGER NOT NULL DEFAULT 20;

UPDATE restaurant_config
   SET order_lead_minutes = pickup_lead_minutes,
       cancel_limit_minutes = pickup_lead_minutes;

-- ORDER_REFUND: devolución a AVAILABLE de créditos ya consumidos cuando se
-- cancela un pedido CONFIRMADO o COMANDADO dentro del límite de cancelación.
-- Se distingue de DIRECT_PURCHASE_REFUND para que el historial sea legible.
ALTER TABLE credit_movement DROP CONSTRAINT chk_credit_movement_type;
ALTER TABLE credit_movement ADD CONSTRAINT chk_credit_movement_type CHECK (type IN
    ('WELCOME_GRANT','PACK_PURCHASE','DIRECT_PURCHASE','DIRECT_PURCHASE_REFUND','COMMIT','RELEASE',
     'CONSUME','EXPIRATION','PAYMENT_REVERSAL','ADMIN_ADJUSTMENT','ORDER_REFUND'));
