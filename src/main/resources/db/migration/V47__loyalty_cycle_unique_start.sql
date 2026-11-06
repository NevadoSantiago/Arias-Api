-- A user never has two cycles starting at the same instant (guards the per-user lock against bugs).
CREATE UNIQUE INDEX uq_loyalty_cycle_user_started ON loyalty_cycle (user_id, started_at);
