-- Real Mercado Pago commission and net amount of each payment, for the admin
-- payments report. Additive: both columns are nullable and existing purchases
-- stay NULL (Mercado Pago did not report them back then).
ALTER TABLE credit_purchase ADD COLUMN mp_fee_cents BIGINT;
ALTER TABLE credit_purchase ADD COLUMN mp_net_received_cents BIGINT;
