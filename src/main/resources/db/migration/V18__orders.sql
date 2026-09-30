-- Modelo de pedido nuevo (unidad 7): orders + order_item conviven con
-- daily_choice en vez de reemplazarla (diseño §Decisión 2). daily_choice
-- queda congelada como historia de solo lectura; todo pedido nuevo — B2C o
-- empleado de empresa — nace acá y consume créditos vía CreditLedgerService.
--
-- Sin UNIQUE(user_id, fecha): a diferencia de daily_choice, un usuario puede
-- tener varios pedidos el mismo día.
CREATE TABLE orders (
    id            BIGSERIAL   PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users(id),
    company_id    BIGINT      REFERENCES company(id),   -- instantánea de user.company, NULL para B2C
    fecha         DATE        NOT NULL,
    pickup_at     TIMESTAMP   NOT NULL,
    estado        VARCHAR(20) NOT NULL,
    credit_total  INTEGER     NOT NULL,
    notas         TEXT,
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now(),
    confirmed_at  TIMESTAMP,
    comandado_at  TIMESTAMP,
    delivered_at  TIMESTAMP,
    cancelled_at  TIMESTAMP,
    CONSTRAINT chk_orders_estado CHECK (estado IN
        ('PENDIENTE','CONFIRMADO','COMANDADO','ENTREGADO','CANCELADO')),
    CONSTRAINT chk_orders_credit_total CHECK (credit_total > 0)
);
CREATE INDEX idx_orders_fecha_pickup   ON orders(fecha, pickup_at);
CREATE INDEX idx_orders_user_fecha     ON orders(user_id, fecha);
CREATE INDEX idx_orders_company_fecha  ON orders(company_id, fecha) WHERE company_id IS NOT NULL;
CREATE INDEX idx_orders_pending_pickup ON orders(pickup_at) WHERE estado = 'PENDIENTE';

-- Ítems del pedido, cada uno con su propia instantánea (nombre del plato,
-- categoría, side, costo en créditos) — reemplaza a precio_snapshot.
CREATE TABLE order_item (
    id              BIGSERIAL    PRIMARY KEY,
    order_id        BIGINT       NOT NULL REFERENCES orders(id),
    dish_id         BIGINT       NOT NULL REFERENCES dish(id),
    side_id         BIGINT       REFERENCES side(id),
    category_id     BIGINT       REFERENCES category(id),
    dish_nombre     VARCHAR(150) NOT NULL,   -- snapshot
    dish_categoria  VARCHAR(100) NOT NULL,   -- snapshot
    side_nombre     VARCHAR(100),            -- snapshot
    credit_cost     INTEGER      NOT NULL,   -- snapshot: reemplaza a precio_snapshot
    notas           TEXT,
    CONSTRAINT chk_order_item_credit_cost CHECK (credit_cost > 0)
);
CREATE INDEX idx_order_item_order ON order_item(order_id);
