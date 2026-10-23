-- Estación de preparación del plato (ruteo de comandas). Independiente de la sección del menú.
ALTER TABLE dish ADD COLUMN station VARCHAR(20) NOT NULL DEFAULT 'COCINA';
ALTER TABLE dish ADD CONSTRAINT chk_dish_station CHECK (station IN ('COCINA','PARRILLA'));
