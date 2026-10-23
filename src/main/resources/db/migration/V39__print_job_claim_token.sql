-- Token único por reclamo (se regenera en cada claim, incluidos los re-ofrecimientos).
-- El agente debe devolverlo en el ack: un ack tardío de un reclamo anterior no puede
-- modificar un trabajo que otro reclamo está imprimiendo. NULL = sin reclamo vigente.
ALTER TABLE print_job ADD COLUMN claim_token VARCHAR(36);
