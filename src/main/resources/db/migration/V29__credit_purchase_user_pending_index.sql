-- B14: GET /api/v1/credits/purchases/pending filtra por usuario + PENDING y
-- ordena por created_at DESC. El único índice existente
-- (idx_credit_purchase_pending, V22) es por created_at para el scheduler y no
-- sirve para buscar por usuario. Parcial: solo las PENDING, que son pocas.
CREATE INDEX idx_credit_purchase_user_pending
    ON credit_purchase(user_id, created_at DESC)
    WHERE status = 'PENDING';
