-- Opt-in (por default) al mail "tu pedido está casi listo para retirar".
ALTER TABLE users
    ADD COLUMN recibe_aviso_retiro BOOLEAN NOT NULL DEFAULT TRUE;
