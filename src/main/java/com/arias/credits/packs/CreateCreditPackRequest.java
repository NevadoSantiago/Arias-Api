package com.arias.credits.packs;

import jakarta.validation.constraints.*;

public record CreateCreditPackRequest(
    /** Opcional: si falta se genera a partir del tipo (compatibilidad con clientes que aún lo envían). */
    @Size(max = 20) String code,
    /** Opcional: si falta se deriva del código (DAY → INDIVIDUAL, WEEK → SUGERIDO, otro → OTRO). */
    CreditPackType packType,
    @NotBlank @Size(max = 100) String nombre,
    @NotNull @Positive Integer creditAmount,
    @NotNull @Positive Long priceCents,
    @NotNull @Min(0) @Max(100) Integer discountPercent,
    @NotNull @PositiveOrZero Integer ordenDisplay
) {}
