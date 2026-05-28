package com.arias.users;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta de empleado vía panel del CompanyAdmin.
 * El usuario queda en whitelist sin password — completa nombre + password en first-login.
 */
public record CreateEmployeeRequest(
    @NotBlank @Email @Size(max = 255) String email
) {}
