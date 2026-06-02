-- Precio acordado entre una empresa y una categoría (tier) de plato.
-- Cada combinación (company, category) tiene un valor en pesos sin decimales.
-- Al crear una empresa nueva o una categoría nueva, se debe asignar precio para
-- TODAS las combinaciones cruzadas — esto se valida en el service.
CREATE TABLE company_category_price (
    company_id  BIGINT NOT NULL REFERENCES company(id)  ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    precio      INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (company_id, category_id)
);

CREATE INDEX idx_company_category_price_category ON company_category_price(category_id);

-- Backfill: para cada empresa × categoría que ya existe, inserta precio=0.
-- El admin lo edita después desde el form de la empresa.
INSERT INTO company_category_price (company_id, category_id, precio)
SELECT c.id, cat.id, 0
FROM company c
CROSS JOIN category cat
ON CONFLICT DO NOTHING;
