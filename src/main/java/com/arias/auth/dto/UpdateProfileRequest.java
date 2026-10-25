package com.arias.auth.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/v1/auth/me}: the logged-in user's editable
 * profile fields. Only the alias today. The 2-50 length rule is enforced on
 * the trimmed value in {@code AuthService#updateNickname}.
 */
public record UpdateProfileRequest(@NotNull String nickname) {}
