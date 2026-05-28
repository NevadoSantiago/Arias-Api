package com.arias.common.security;

import com.arias.users.Role;

/**
 * Representa al usuario autenticado en el contexto de Spring Security.
 * Se popula desde los claims del JWT por el {@link JwtAuthenticationFilter}.
 *
 * <p>Los controllers pueden obtenerlo con {@code @AuthenticationPrincipal JwtUser user}.
 */
public record JwtUser(
    Long userId,
    String email,
    Role role,
    Long companyId,   // null para SUPER_ADMIN
    Long categoryId   // null para no-EMPLOYEE
) {
    public boolean isSuperAdmin() {
        return role == Role.SUPER_ADMIN;
    }

    public boolean isCompanyAdmin() {
        return role == Role.COMPANY_ADMIN;
    }

    public boolean isEmployee() {
        return role == Role.EMPLOYEE;
    }
}
