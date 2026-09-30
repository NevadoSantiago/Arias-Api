package com.arias.credits.packs;

import jakarta.validation.constraints.*;

public record CreateCreditPackRequest(
    @NotBlank @Size(max = 20) String code,
    @NotBlank @Size(max = 100) String nombre,
    @NotNull @Positive Integer creditAmount,
    @NotNull @Positive Long priceCents,
    @NotNull @Min(0) @Max(100) Integer discountPercent,
    @NotNull @PositiveOrZero Integer ordenDisplay
) {}
