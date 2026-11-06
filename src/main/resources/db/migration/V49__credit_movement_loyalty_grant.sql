-- Loyalty line: the free lunch earned at 18 lunches is credited with a new movement type.
-- Replaces chk_credit_movement_type (same pattern as V34) keeping every earlier value.
ALTER TABLE credit_movement DROP CONSTRAINT chk_credit_movement_type;
ALTER TABLE credit_movement ADD CONSTRAINT chk_credit_movement_type CHECK (type IN
    ('WELCOME_GRANT','PACK_PURCHASE','DIRECT_PURCHASE','DIRECT_PURCHASE_REFUND','COMMIT','RELEASE',
     'CONSUME','EXPIRATION','PAYMENT_REVERSAL','ADMIN_ADJUSTMENT','ORDER_REFUND','LOYALTY_GRANT'));
