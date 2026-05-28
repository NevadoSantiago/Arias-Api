package com.arias.auth.dto;

import com.arias.users.Role;

public record MeResponse(
    Long id,
    String email,
    String firstName,
    String lastName,
    Role role,
    Long companyId,
    String companyName,
    Long categoryId
) {}
