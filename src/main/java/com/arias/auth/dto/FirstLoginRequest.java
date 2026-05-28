package com.arias.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FirstLoginRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 2, max = 100) String firstName,
    @Size(max = 100) String lastName,
    @NotBlank @Size(min = 8, max = 72) String password
) {}
