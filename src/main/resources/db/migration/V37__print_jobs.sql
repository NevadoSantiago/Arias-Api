-- Impresión automática de comandas.
--
-- printer: una ticketera por estación (COCINA / PARRILLA), editable desde el admin.
-- print_job: cola de trabajos de impresión. El agente local los reclama por polling,
--   imprime en host:port (copiados de printer al encolar) y confirma.

CREATE TABLE printer (
    station     VARCHAR(20)  PRIMARY KEY,
    host        VARCHAR(45)  NOT NULL,
    port        INTEGER      NOT NULL,
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_printer_station CHECK (station IN ('COCINA','PARRILLA')),
    CONSTRAINT chk_printer_port    CHECK (port BETWEEN 1 AND 65535)
);

INSERT INTO printer (station, host, port) VALUES
    ('COCINA',   '192.168.0.121', 9100),
    ('PARRILLA', '192.168.0.120', 9100);

CREATE TABLE print_job (
    id            BIGSERIAL    PRIMARY KEY,
    order_id      BIGINT       NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    station       VARCHAR(20)  NOT NULL,
    host          VARCHAR(45)  NOT NULL,
    port          INTEGER      NOT NULL,
    data          BYTEA        NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts      INTEGER      NOT NULL DEFAULT 0,
    available_at  TIMESTAMP    NOT NULL,
    claimed_at    TIMESTAMP,
    printed_at    TIMESTAMP,
    last_error    VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_print_job_station CHECK (station IN ('COCINA','PARRILLA')),
    CONSTRAINT chk_print_job_status  CHECK (status IN ('PENDING','CLAIMED','PRINTED','FAILED','CANCELLED')),
    CONSTRAINT chk_print_job_port    CHECK (port BETWEEN 1 AND 65535)
);

-- Claim del agente: PENDING disponibles, y CLAIMED vencidos (re-oferta).
CREATE INDEX idx_print_job_pending ON print_job(available_at) WHERE status = 'PENDING';
CREATE INDEX idx_print_job_claimed ON print_job(claimed_at)   WHERE status = 'CLAIMED';
-- Estado por pedido (tablero) y cancelación al deshacer.
CREATE INDEX idx_print_job_order   ON print_job(order_id);
