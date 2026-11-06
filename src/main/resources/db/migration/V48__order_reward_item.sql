-- Loyalty reward lines of an order (the gift dessert). Kept apart from order_item on purpose: they cost no
-- lunch credits (order_item.credit_cost > 0) and never enter the credit math.
-- reward_id is NOT unique: a returned reward can be redeemed again on another order, and the line of a
-- cancelled order stays as history.
CREATE TABLE order_reward_item (
    id              BIGSERIAL     PRIMARY KEY,
    order_id        BIGINT        NOT NULL REFERENCES orders(id),
    reward_id       BIGINT        NOT NULL REFERENCES loyalty_reward(id),
    dessert_id      BIGINT        NOT NULL REFERENCES dessert(id),
    dessert_nombre  VARCHAR(150)  NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_reward_item_order ON order_reward_item (order_id);
