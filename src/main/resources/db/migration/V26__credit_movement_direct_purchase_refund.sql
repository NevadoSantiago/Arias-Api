-- Unidad B7: cuando una compra DIRECT se aprueba pero el pedido asociado ya
-- está CANCELADO (el cliente lo canceló, o el corte automático lo canceló
-- mientras el pago seguía en curso), los almuerzos pagados no se pierden:
-- se acreditan a AVAILABLE en vez de a COMMITTED (que exigiría reabrir un
-- pedido ya cerrado). DIRECT_PURCHASE_REFUND se distingue de PACK_PURCHASE
-- a propósito — es una compra DIRECT reconducida, no la compra de un
-- paquete — para que el historial del libro mayor sea legible.
ALTER TABLE credit_movement DROP CONSTRAINT chk_credit_movement_type;
ALTER TABLE credit_movement ADD CONSTRAINT chk_credit_movement_type CHECK (type IN
    ('WELCOME_GRANT','PACK_PURCHASE','DIRECT_PURCHASE','DIRECT_PURCHASE_REFUND','COMMIT','RELEASE',
     'CONSUME','EXPIRATION','PAYMENT_REVERSAL','ADMIN_ADJUSTMENT'));
