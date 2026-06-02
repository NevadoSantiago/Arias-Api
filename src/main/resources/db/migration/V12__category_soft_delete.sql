-- Soft delete de categoría. NULL = visible. Cuando se setea, la categoría
-- desaparece del listado admin y de las queries de visibilidad/dropdowns
-- pero los registros que la referencian (dish, user, company_category_price)
-- siguen apuntando a su id.
ALTER TABLE category
    ADD COLUMN deleted_at TIMESTAMP;
