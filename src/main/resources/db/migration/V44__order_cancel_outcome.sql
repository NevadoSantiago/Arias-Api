-- Outcome of a cancelled order: whether the lunches went back to the customer's balance
-- (cancelled in time) or stayed consumed (cancelled after the refund window), and who/what cancelled it.
ALTER TABLE orders
    ADD COLUMN cancel_refunded BOOLEAN,
    ADD COLUMN cancel_reason   VARCHAR(20);

-- Until now every cancellation returned the lunches.
UPDATE orders SET cancel_refunded = TRUE WHERE estado = 'CANCELADO';
