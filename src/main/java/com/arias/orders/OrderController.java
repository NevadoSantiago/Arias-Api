package com.arias.orders;

import com.arias.common.security.JwtUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('EMPLOYEE', 'COMPANY_ADMIN')")
public class OrderController {

    private final OrderService orderService;
    private final Clock clock;

    @GetMapping("/today")
    public ResponseEntity<DailyChoiceDto> getToday(@AuthenticationPrincipal JwtUser user) {
        Optional<DailyChoiceDto> order = orderService.findTodayOrderDto(user.userId());
        return order
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/week")
    public List<DailyChoiceDto> getWeek(@AuthenticationPrincipal JwtUser user) {
        return orderService.findWeekOrders(user.userId());
    }

    @GetMapping("/preferences/{dishId}")
    public ResponseEntity<DishPreferenceDto> getDishPreference(
        @AuthenticationPrincipal JwtUser user,
        @PathVariable Long dishId
    ) {
        return orderService.findDishPreference(user.userId(), dishId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    public DailyChoiceDto place(
        @AuthenticationPrincipal JwtUser user,
        @Valid @RequestBody PlaceOrderRequest req
    ) {
        return orderService.placeAndReturnDto(user.userId(), req);
    }

    @PutMapping("/{id}")
    public DailyChoiceDto update(
        @AuthenticationPrincipal JwtUser user,
        @PathVariable Long id,
        @Valid @RequestBody PlaceOrderRequest req
    ) {
        return orderService.updateAndReturnDto(user.userId(), id, req);
    }

    @DeleteMapping("/today")
    public ResponseEntity<Void> cancelOrder(
        @AuthenticationPrincipal JwtUser user,
        @RequestParam(required = false) LocalDate fecha
    ) {
        orderService.cancelByDate(user.userId(), fecha != null ? fecha : LocalDate.now(clock));
        return ResponseEntity.noContent().build();
    }
}
