-- Loyalty line ("Linea de beneficios"): personal 2-month cycles and the rewards earned in them.
-- The lunch count of a cycle is never stored: it is derived from the delivered B2C orders of the user.
CREATE TABLE loyalty_cycle (
    id          BIGSERIAL  PRIMARY KEY,
    user_id     BIGINT     NOT NULL REFERENCES users(id),
    started_at  TIMESTAMP  NOT NULL,
    ends_at     TIMESTAMP  NOT NULL,
    created_at  TIMESTAMP  NOT NULL DEFAULT now()
);

CREATE INDEX idx_loyalty_cycle_user_started ON loyalty_cycle (user_id, started_at);

-- One reward per cycle and milestone (UNIQUE): a milestone is never granted twice, even after an
-- undo + re-delivery. used_* is filled when the reward is redeemed (dessert on an order).
CREATE TABLE loyalty_reward (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES users(id),
    cycle_id       BIGINT       NOT NULL REFERENCES loyalty_cycle(id),
    milestone      INTEGER      NOT NULL,
    kind           VARCHAR(20)  NOT NULL,
    earned_at      TIMESTAMP    NOT NULL,
    expires_at     TIMESTAMP,
    used_at        TIMESTAMP,
    used_order_id  BIGINT       REFERENCES orders(id),
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_loyalty_reward_kind CHECK (kind IN ('DESSERT', 'LUNCH', 'DISCOUNT')),
    CONSTRAINT uq_loyalty_reward_cycle_milestone UNIQUE (cycle_id, milestone)
);

CREATE INDEX idx_loyalty_reward_user ON loyalty_reward (user_id);
