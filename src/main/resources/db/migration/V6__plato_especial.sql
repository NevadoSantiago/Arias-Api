ALTER TABLE dish ADD COLUMN especial BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE dish_dia_semana (
    dish_id BIGINT NOT NULL REFERENCES dish(id) ON DELETE CASCADE,
    dia     VARCHAR(20) NOT NULL,
    PRIMARY KEY (dish_id, dia),
    CONSTRAINT chk_dia_semana CHECK (dia IN ('LUNES', 'MARTES', 'MIERCOLES', 'JUEVES', 'VIERNES', 'SABADO', 'DOMINGO'))
);
