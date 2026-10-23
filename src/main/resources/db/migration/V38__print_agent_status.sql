-- Latido del agente de impresión: una sola fila (id = 1) que cada claim actualiza.
-- last_seen_at NULL = el agente nunca se conectó.
CREATE TABLE print_agent_status (
    id            SMALLINT  PRIMARY KEY,
    last_seen_at  TIMESTAMP,
    CONSTRAINT chk_print_agent_status_singleton CHECK (id = 1)
);

INSERT INTO print_agent_status (id, last_seen_at) VALUES (1, NULL);
