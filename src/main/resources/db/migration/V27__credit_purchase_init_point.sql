-- Unidad B7 (pedido del usuario, resumir un pago directo abandonado):
-- persiste la URL de checkout de Mercado Pago de la compra para poder
-- devolverla otra vez sin crear un segundo cobro cuando el cliente cierra
-- Mercado Pago sin pagar y vuelve a intentarlo
-- (GET /api/v2/orders/{id}/direct-checkout).
ALTER TABLE credit_purchase ADD COLUMN init_point TEXT;
