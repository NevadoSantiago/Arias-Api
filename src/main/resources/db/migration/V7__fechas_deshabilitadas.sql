CREATE TABLE fecha_deshabilitada (
    id     BIGSERIAL PRIMARY KEY,
    fecha  DATE NOT NULL,
    motivo VARCHAR(200),
    CONSTRAINT uq_fecha_deshabilitada UNIQUE (fecha)
);
