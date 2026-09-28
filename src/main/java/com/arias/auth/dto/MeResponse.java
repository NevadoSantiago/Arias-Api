package com.arias.auth.dto;

import com.arias.users.Role;

/**
 * @param nickname        apodo capturado en el autorregistro B2C; {@code
 *                        null} en cuentas que no lo tienen (empresa).
 * @param displayName     nombre con el que el mostrador llama al cliente
 *                        (apodo, luego nombre y apellido, luego correo): el
 *                        MISMO que ve la cocina, ver {@code User#displayName}.
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
    String nickname,
    String displayName,
    boolean emailVerified,
    boolean profileComplete
) {}
