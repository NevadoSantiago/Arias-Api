package com.arias.users;

import java.time.Instant;

/**
 * Vista del User como "empleado de empresa" — usada por el panel del CompanyAdmin.
 * NO incluye campos sensibles (password_hash) ni administrativos (role, company).
 */
public record EmployeeDto(
    Long id,
    String email,
    String firstName,
    String lastName,
    Long categoryId,
    String categoryNombre,
    Boolean active,
    Boolean firstLoginPending,
    Instant lastLoginAt,
    Instant createdAt,
    Role role
) {
    public static EmployeeDto from(User u) {
        return new EmployeeDto(
            u.getId(),
            u.getEmail(),
            u.getFirstName(),
            u.getLastName(),
            u.getCategory() != null ? u.getCategory().getId() : null,
            u.getCategory() != null ? u.getCategory().getNombre() : null,
            u.getActive(),
            u.getPasswordHash() == null, // sin password = pendiente de first-login
            u.getLastLoginAt(),
            u.getCreatedAt(),
            u.getRole()
        );
    }
}
