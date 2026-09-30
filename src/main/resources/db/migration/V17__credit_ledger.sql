-- Libro mayor de créditos B2C: saldos materializados (credit_wallet) +
-- movimientos inmutables (credit_movement), ver diseño §Decisión 3.
--
-- Cada movimiento lleva dos deltas con signo (delta_available, delta_committed),
-- lo que convierte el libro mayor en partida doble entre los dos buckets y hace
-- verificable el invariante: wallet.available == SUM(delta_available) y
-- wallet.committed == SUM(delta_committed).
--
-- La concurrencia se resuelve con SELECT ... FOR UPDATE sobre credit_wallet
-- (una fila por usuario), no con @Version optimista: el webhook de Mercado
-- Pago no puede reintentar barato.
CREATE TABLE credit_wallet (
    user_id    BIGINT    PRIMARY KEY REFERENCES users(id),
    available  INTEGER   NOT NULL DEFAULT 0,
    committed  INTEGER   NOT NULL DEFAULT 0,
    expires_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_credit_wallet_non_negative CHECK (available >= 0 AND committed >= 0)
);

-- order_id y purchase_id son FKs lógicas (no constraint): orders llega en V18,
-- credit_purchase en V19. credit_movement es append-only — nunca se actualiza
-- ni se borra una fila existente.
CREATE TABLE credit_movement (
    id               BIGSERIAL   PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users(id),
    type             VARCHAR(30) NOT NULL,
    delta_available  INTEGER     NOT NULL,
    delta_committed  INTEGER     NOT NULL,
    order_id         BIGINT,       -- FK lógica a orders (V18)
    purchase_id      UUID,         -- FK lógica a credit_purchase (V19)
    description      VARCHAR(255),
    created_at       TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT chk_credit_movement_type CHECK (type IN
        ('WELCOME_GRANT','PACK_PURCHASE','DIRECT_PURCHASE','COMMIT','RELEASE',
         'CONSUME','EXPIRATION','PAYMENT_REVERSAL','ADMIN_ADJUSTMENT'))
);
CREATE INDEX idx_credit_movement_user ON credit_movement(user_id, created_at DESC);

-- Otorgamiento único del almuerzo de bienvenida: se defiende en el esquema,
-- no en un `if` (ver diseño §Decisión 10). Un usuario no puede tener más de
-- un movimiento WELCOME_GRANT, sin importar cuántas veces reintente el flujo.
CREATE UNIQUE INDEX uq_credit_movement_welcome
    ON credit_movement(user_id) WHERE type = 'WELCOME_GRANT';
