-- Soft delete del plato. NULL = activo. Cuando se setea, el plato
-- desaparece del listado del admin pero se preservan los pedidos
-- históricos (DailyChoice) y los snapshots de nombre/categoría/side.
ALTER TABLE dish
    ADD COLUMN deleted_at TIMESTAMP;
