-- Unidad B7 (feature b2c-ordering-redesign): estado "esperando pago" para
-- pedidos creados por POST /api/v2/orders/direct-checkout — el cliente sin
-- saldo suficiente reserva stock y paga el pedido entero por Mercado Pago,
-- SIN comprometer créditos hasta que el pago se aprueba. Cierra el camino
-- viejo de doble cobro (una compra DIRECT sobre un pedido PENDIENTE ya
-- comprometía créditos en OrderPlacementService.place()).
ALTER TABLE orders DROP CONSTRAINT chk_orders_estado;
ALTER TABLE orders ADD CONSTRAINT chk_orders_estado CHECK (estado IN
    ('PENDIENTE_PAGO','PENDIENTE','CONFIRMADO','COMANDADO','ENTREGADO','CANCELADO'));

-- Mismo patrón que idx_orders_pending_pickup: el scheduler de consumo
-- (OrderConsumptionScheduler) ahora también busca por pickup_at los pedidos
-- PENDIENTE_PAGO cuyo corte llegó sin pago resuelto, para cancelarlos.
CREATE INDEX idx_orders_awaiting_payment_pickup ON orders(pickup_at) WHERE estado = 'PENDIENTE_PAGO';
