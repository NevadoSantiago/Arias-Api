package com.arias.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Completa los datos que Google no provee — teléfono y apodo para el ticket
 * de cocina (diseño §Decisión 9). Requiere sesión ya válida: el alta/login
 * con Google emite tokens igual, pero {@code me().profileComplete} queda en
 * {@code false} y pedir/comprar responde {@code 409 profile-incomplete}
 * hasta que se llame este endpoint.
 */
public record CompleteProfileRequest(
    @NotBlank @Size(max = 30) String phone,
    @NotBlank @Size(min = 2, max = 50) String nickname
) {}
