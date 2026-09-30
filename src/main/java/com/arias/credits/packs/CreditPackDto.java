package com.arias.credits.packs;

public record CreditPackDto(
    Long id,
    String code,
    String nombre,
    Integer creditAmount,
    Long priceCents,
    Integer discountPercent,
    Integer ordenDisplay,
    Boolean enabled
) {
    public static CreditPackDto from(CreditPack pack) {
        return new CreditPackDto(
            pack.getId(),
            pack.getCode(),
            pack.getNombre(),
            pack.getCreditAmount(),
            pack.getPriceCents(),
            pack.getDiscountPercent(),
            pack.getOrdenDisplay(),
            pack.getEnabled()
        );
    }
}
