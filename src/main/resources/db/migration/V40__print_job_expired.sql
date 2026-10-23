-- Impresion automatica de comandas: estado terminal EXPIRED.
--
-- Un trabajo que no se imprimio dentro de arias.print-agent.job-ttl desde que se encolo
-- (created_at) se descarta como EXPIRED y nunca se imprime; el admin puede reimprimir.

ALTER TABLE print_job DROP CONSTRAINT chk_print_job_status;
ALTER TABLE print_job ADD CONSTRAINT chk_print_job_status
    CHECK (status IN ('PENDING','CLAIMED','PRINTED','FAILED','CANCELLED','EXPIRED'));
