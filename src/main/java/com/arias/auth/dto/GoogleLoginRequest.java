package com.arias.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Alta/login con Google (spec {@code self-registration}, "Inicio de sesión
 * con Google"). El frontend obtiene el ID token vía Google Identity
 * Services y lo manda tal cual — el backend es quien lo valida (diseño
 * §Decisión 9); nunca se confía en un email/sub mandado por el cliente sin
 * pasar por {@code GoogleIdTokenVerifier}.
 */
public record GoogleLoginRequest(
    @NotBlank String idToken
) {}
