package com.arias.auth.dto;

import com.arias.users.Role;

/**
 * @param emailVerified   {@code true} si la cuenta ya validó su correo (por
 *                        verificación de correo o por Google) — diseño
 *                        §Decisión 9/10.
 * @param profileComplete {@code true} si tiene teléfono y apodo. En
 *                        {@code false} hasta {@code POST
 *                        /api/v1/auth/complete-profile} para cuentas
 *                        creadas vía Google (diseño §Decisión 9).
 */
public record MeResponse(
    Long id,
    String email,
    String firstName,
    String lastName,
    Role role,
    Long companyId,
    String companyName,
    Long categoryId,
    boolean emailVerified,
    boolean profileComplete
) {}
