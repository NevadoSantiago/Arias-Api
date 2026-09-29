-- Unidad B13 (pedido del usuario 2026-09-29, pago parcial): un pedido directo
-- (PENDIENTE_PAGO) usa primero los almuerzos disponibles del cliente y paga
-- solo el resto por Mercado Pago. Esta columna guarda cuantos almuerzos del
-- pedido se reservaron (COMMITTED) del saldo al crearlo, para liberarlos si
-- el pago no se completa. Los pedidos existentes quedan en 0, que es el
-- significado correcto: hasta ahora el pedido directo se pagaba entero por
-- Mercado Pago.
ALTER TABLE orders ADD COLUMN credits_from_balance INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders ADD CONSTRAINT chk_orders_credits_from_balance
    CHECK (credits_from_balance >= 0 AND credits_from_balance <= credit_total);
