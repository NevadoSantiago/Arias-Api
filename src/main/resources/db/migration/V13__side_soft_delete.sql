-- Soft delete de acompañamientos. NULL = visible. Cuando se setea, el side
-- desaparece del listado admin y de los dropdowns, pero los registros que lo
-- referencian (daily_choice.side_id, dish_side) siguen apuntando a su id y
-- los snapshots (side_nombre) quedan intactos.
ALTER TABLE side
    ADD COLUMN deleted_at TIMESTAMP;
