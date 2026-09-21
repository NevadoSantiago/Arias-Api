package com.arias.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta pública de autorregistro (spec {@code self-registration}, "Alta pública
 * con datos mínimos"). Sin password — la cuenta se activa vía verificación de
 * correo (diseño §Decisión 10).
 */
public record RegisterRequest(
    @NotBlank @Size(min = 2, max = 100) String firstName,
    @Size(max = 100) String lastName,
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(max = 30) String phone,
    @NotBlank @Size(min = 2, max = 50) String nickname
) {}
