-- Costo en créditos ("almuerzos") por categoría. Es la moneda única del
-- pivote B2C: reemplaza a company_category_price como fuente de costo para
-- el consumo de pedidos nuevos (orders/order_item, V18). company_category_price
-- sigue existiendo sin cambios — sigue siendo la fuente de BillingService.
--
-- Default 1 para todas las categorías existentes, incluida la estándar:
-- ningún pedido histórico ni comportamiento actual cambia con esta migración.
ALTER TABLE category ADD COLUMN credit_cost INTEGER NOT NULL DEFAULT 1;
ALTER TABLE category ADD CONSTRAINT chk_category_credit_cost CHECK (credit_cost > 0);
