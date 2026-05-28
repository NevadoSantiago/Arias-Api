package com.arias.users;

import jakarta.validation.constraints.NotNull;

public record UpdateEmployeeCategoryRequest(
    @NotNull Long categoryId
) {}
