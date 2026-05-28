-- Soft delete de usuarios. Por default NULL = no borrado.
-- Las queries de listado y findByEmail filtran WHERE deleted_at IS NULL,
-- así un user borrado es invisible para el sistema pero las orders/snapshots
-- que lo referencian siguen siendo válidas.

ALTER TABLE users
    ADD COLUMN deleted_at TIMESTAMPTZ NULL;

-- Index parcial para acelerar los listings (la mayoría de queries filtran
-- por deleted_at IS NULL — un parcial es mucho más chico que un index normal)
CREATE INDEX idx_users_deleted_at_null ON users (id) WHERE deleted_at IS NULL;
