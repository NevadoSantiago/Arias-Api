-- Autorregistro público (spec self-registration): teléfono normalizado E.164,
-- apodo para el ticket de cocina, marca de verificación de correo y el
-- google_sub que la unidad 5 usará para el login con Google.
ALTER TABLE users ADD COLUMN phone             VARCHAR(30);
ALTER TABLE users ADD COLUMN nickname          VARCHAR(50);
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMP;
ALTER TABLE users ADD COLUMN google_sub        VARCHAR(64);

-- Unicidad parcial: solo aplica a filas con valor y no borradas (soft-delete).
CREATE UNIQUE INDEX uq_users_phone      ON users(phone)      WHERE phone IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_users_google_sub ON users(google_sub) WHERE google_sub IS NOT NULL;

-- Backfill: todo usuario preexistente (altas por lista blanca de un
-- COMPANY_ADMIN) queda verificado — ese canal de alta YA es la verificación.
-- Ningún empleado de empresa queda bloqueado por este cambio.
UPDATE users SET email_verified_at = created_at WHERE email_verified_at IS NULL;

-- Mismo patrón que password_reset_token (V4): token de un solo uso, hasheado.
CREATE TABLE email_verification_token (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMP   NOT NULL,
    used_at     TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_email_verification_token_hash UNIQUE (token_hash)
);
CREATE INDEX idx_email_verification_token_user ON email_verification_token(user_id);
