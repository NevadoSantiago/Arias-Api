-- Postres: catalogo propio usado solo como premio del programa de fidelizacion.
-- No se muestran en el menu ni se cobran en almuerzos/creditos. Migracion aditiva.
CREATE TABLE dessert (
    id                   BIGSERIAL    PRIMARY KEY,
    nombre               VARCHAR(150) NOT NULL,
    descripcion          TEXT,
    foto_url             VARCHAR(500),
    stock_diario_default INT          NOT NULL DEFAULT 0 CONSTRAINT chk_dessert_stock_diario CHECK (stock_diario_default >= 0),
    stock_actual         INT          NOT NULL DEFAULT 0 CONSTRAINT chk_dessert_stock_actual CHECK (stock_actual >= 0),
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT now(),
    deleted_at           TIMESTAMP
);

CREATE INDEX idx_dessert_enabled ON dessert (enabled) WHERE deleted_at IS NULL;
