package com.arias.auth.dto;

/**
 * {@code welcomeLunchGranted} es {@code true} únicamente cuando ESTA llamada
 * otorgó el almuerzo de bienvenida (spec {@code self-registration},
 * "Otorgamiento único") — nunca en un login posterior de la misma cuenta.
 * Campo aditivo: {@code false} en {@code login}/{@code first-login}/{@code
 * refresh}, real en {@code verify-email}/{@code google}.
 */
public record TokenResponse(
    String accessToken,
    boolean welcomeLunchGranted
) {}
