package com.arias.orders;

/** The loyalty reward line of an order as the apps see it: the gift dessert. */
public record RewardItemDto(Long dessertId, String nombre) {

    public static RewardItemDto from(OrderRewardItem item) {
        return new RewardItemDto(item.getDessert().getId(), item.getDessertNombre());
    }
}
