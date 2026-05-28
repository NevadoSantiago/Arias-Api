package com.arias.auth.dto;

public record CheckEmailResponse(
    boolean requiresFirstLogin
) {}
