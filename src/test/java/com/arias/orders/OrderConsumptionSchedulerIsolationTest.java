package com.arias.orders;

import com.arias.restaurantconfig.RestaurantConfig;
import com.arias.restaurantconfig.RestaurantConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Per-order failure isolation of {@link OrderConsumptionScheduler} (no shared transaction). */
class OrderConsumptionSchedulerIsolationTest {

    private static final Instant NOW = Instant.parse("2026-03-10T15:00:00Z");

    @Test
    @DisplayName("consumeDueOrders(): one failing order is skipped and the others are still comandadas")
    void failingOrderDoesNotBlockTheOthers() {
        OrderRepository orderRepo = mock(OrderRepository.class);
        OrderKitchenService kitchen = mock(OrderKitchenService.class);
        RestaurantConfigRepository configRepo = mock(RestaurantConfigRepository.class);
        OrderPlacementService placement = mock(OrderPlacementService.class);
        RestaurantConfig config = mock(RestaurantConfig.class);
        when(config.getPickupLeadMinutes()).thenReturn(20);
        when(config.getOrderLeadMinutes()).thenReturn(20);
        when(configRepo.getSingleton()).thenReturn(config);

        Order first = Order.builder().id(1L).build();
        Order second = Order.builder().id(2L).build();
        Order leftover = Order.builder().id(3L).build();
        when(orderRepo.findByEstadoAndPickupAtLessThanEqual(eq(OrderEstado.PENDIENTE), any()))
            .thenReturn(List.of(first, second));
        when(orderRepo.findByEstadoAndPickupAtLessThanEqual(eq(OrderEstado.CONFIRMADO), any()))
            .thenReturn(List.of(leftover));
        when(orderRepo.findByEstadoAndPickupAtLessThanEqual(eq(OrderEstado.PENDIENTE_PAGO), any()))
            .thenReturn(List.of());
        doThrow(new IllegalStateException("boom")).when(kitchen).autoComandar(eq(1L), any(), any());

        var scheduler = new OrderConsumptionScheduler(orderRepo, kitchen, configRepo, placement,
            Clock.fixed(NOW, ZoneId.of("UTC")));

        scheduler.consumeDueOrders();

        verify(kitchen).autoComandar(eq(1L), any(), any());
        verify(kitchen).autoComandar(eq(2L), any(), any());
        verify(kitchen).autoComandar(eq(3L), any(), any());
    }
}
