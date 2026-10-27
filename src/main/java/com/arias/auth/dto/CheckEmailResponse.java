package com.arias.auth.dto;

import com.arias.auth.AccountStatus;

/**
 * @param requiresFirstLogin kept for the frontend still in production; true iff {@code status == FIRST_LOGIN}
 */
public record CheckEmailResponse(
    boolean requiresFirstLogin,
    AccountStatus status
) {}
