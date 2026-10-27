-- Account discount: at most one per user, ever (UNIQUE user_id). Granted by the daily
-- marketing campaign, valid for a few days, usable once on any credit purchase.
-- reserved_purchase_id is set while a PENDING purchase holds the discount and cleared when
-- that purchase is rejected, cancelled or expired; consumed_* is set when it is approved.
CREATE TABLE account_discount (
    id                    BIGSERIAL    PRIMARY KEY,
    user_id               BIGINT       NOT NULL UNIQUE REFERENCES users(id),
    percent               INTEGER      NOT NULL,
    granted_at            TIMESTAMP    NOT NULL,
    expires_at            TIMESTAMP    NOT NULL,
    reserved_purchase_id  UUID         REFERENCES credit_purchase(id),
    consumed_at           TIMESTAMP,
    consumed_purchase_id  UUID         REFERENCES credit_purchase(id),
    source                VARCHAR(50)  NOT NULL,
    CONSTRAINT chk_account_discount_percent CHECK (percent > 0 AND percent < 100)
);

-- Price before the account discount and the percent applied; NULL on purchases without one.
-- amount_cents stays the amount actually charged.
ALTER TABLE credit_purchase
    ADD COLUMN list_amount_cents BIGINT,
    ADD COLUMN discount_percent  INTEGER;
