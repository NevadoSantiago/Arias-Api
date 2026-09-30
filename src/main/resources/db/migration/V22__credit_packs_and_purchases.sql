-- Unidad 11 (diseño §Modelo de datos): catálogo de paquetes de créditos y
-- registro de compras/pagos con Mercado Pago. Aditiva — ninguna tabla
-- existente se toca.

CREATE TABLE credit_pack (
    id               BIGSERIAL    PRIMARY KEY,
    code             VARCHAR(20)  NOT NULL UNIQUE,   -- DAY | WEEK | MONTH
    nombre           VARCHAR(100) NOT NULL,
    credit_amount    INTEGER      NOT NULL,
    price_cents      BIGINT       NOT NULL,          -- ARS en centavos, autoritativo (discount_percent es solo presentación)
    discount_percent INTEGER      NOT NULL DEFAULT 0,
    orden_display    INTEGER      NOT NULL DEFAULT 0,
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    deleted_at       TIMESTAMP,
    CONSTRAINT chk_credit_pack_amount CHECK (credit_amount > 0 AND price_cents > 0)
);

CREATE TABLE credit_purchase (
    id                UUID         PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users(id),
    type              VARCHAR(10)  NOT NULL,          -- PACK | DIRECT
    pack_id           BIGINT       REFERENCES credit_pack(id),
    order_id          BIGINT       REFERENCES orders(id),
    credit_amount     INTEGER      NOT NULL,
    amount_cents      BIGINT       NOT NULL,
    currency          VARCHAR(3)   NOT NULL DEFAULT 'ARS',
    status            VARCHAR(20)  NOT NULL,
    mp_preference_id  VARCHAR(100),
    mp_payment_id     VARCHAR(50)  UNIQUE,            -- idempotencia del webhook (enforcement real, no un simple `if`)
    mp_status_detail  VARCHAR(100),
    -- Créditos ya revertidos acumulados para esta compra — permite calcular el
    -- DELTA correcto ante reembolsos parciales encadenados (decisión del
    -- orquestador que resuelve la unidad 9, ver tasks.md unidad 11.5): la
    -- reversión se calcula proporcional a transaction_amount_refunded, nunca
    -- se vuelve a aplicar lo ya revertido, y nunca excede credit_amount.
    credits_reversed  INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now(),
    credited_at       TIMESTAMP,
    reversed_at       TIMESTAMP,
    CONSTRAINT chk_credit_purchase_status CHECK (status IN
        ('PENDING','APPROVED','REJECTED','CANCELLED','REVERSED','EXPIRED','IN_MEDIATION')),
    CONSTRAINT chk_credit_purchase_target CHECK (
        (type = 'PACK' AND pack_id IS NOT NULL) OR (type = 'DIRECT' AND order_id IS NOT NULL)),
    CONSTRAINT chk_credit_purchase_credits_reversed CHECK (
        credits_reversed >= 0 AND credits_reversed <= credit_amount)
);
CREATE INDEX idx_credit_purchase_pending ON credit_purchase(created_at) WHERE status = 'PENDING';
