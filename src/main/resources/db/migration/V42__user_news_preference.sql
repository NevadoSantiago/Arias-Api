-- Opt-in (por default) a los mails de novedades / marketing.
ALTER TABLE users
    ADD COLUMN recibe_novedades BOOLEAN NOT NULL DEFAULT TRUE;
