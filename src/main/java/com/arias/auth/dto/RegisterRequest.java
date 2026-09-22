package com.arias.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta pública de autorregistro (spec {@code self-registration}, "Alta pública
 * con datos mínimos"). Requiere password (misma política mínima que
 * {@code FirstLoginRequest}/{@code ResetPasswordRequest}: 8-72 caracteres) —
 * sin ella, una cuenta sin Google queda sin forma de autenticarse ni de
 * recuperar acceso vía forgot-password.
 */
public record RegisterRequest(
    @NotBlank @Size(min = 2, max = 100) String firstName,
    @Size(max = 100) String lastName,
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(max = 30) String phone,
    @NotBlank @Size(min = 2, max = 50) String nickname,
    @NotBlank @Size(min = 8, max = 72) String password
) {}
