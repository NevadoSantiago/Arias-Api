package com.arias.marketing;

import com.arias.credits.discount.AccountDiscountService;
import com.arias.email.OutgoingEmail;
import com.arias.orders.Order;
import com.arias.orders.OrderItem;
import com.arias.orders.OrderRepository;
import com.arias.users.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grants the one-time account discount to the B2C customers who ate on a given day and
 * prepares their email. Transactional so grants commit together; the caller sends the
 * emails afterwards, once the grants are durable.
 */
@Service
@RequiredArgsConstructor
public class ReviewDiscountCampaign {

    static final int DISCOUNT_PERCENT = 10;
    static final String SOURCE = "REVIEW_CAMPAIGN";

    private final OrderRepository orderRepo;
    private final AccountDiscountService discountService;
    private final ReviewDiscountEmail email;

    /**
     * Users with a COMANDADO/ENTREGADO B2C order on {@code fecha} who do not already have a
     * discount get one; those who accept news mail and have an address get an email.
     */
    @Transactional
    public List<OutgoingEmail> grantAndPrepareEmails(LocalDate fecha) {
        // Newest order first, so the first order seen per user is their latest of the day.
        Map<Long, Order> latestByUser = new LinkedHashMap<>();
        for (Order o : orderRepo.findOrdersForReviewCampaign(fecha)) {
            latestByUser.putIfAbsent(o.getUser().getId(), o);
        }

        List<OutgoingEmail> emails = new ArrayList<>();
        for (Order order : latestByUser.values()) {
            User user = order.getUser();
            if (!discountService.grantIfAbsent(user, DISCOUNT_PERCENT, SOURCE)) {
                continue;
            }
            if (!Boolean.TRUE.equals(user.getRecibeNovedades())
                || user.getEmail() == null || user.getEmail().isBlank()) {
                continue;
            }
            emails.add(email.build(user, firstDishName(order)));
        }
        return emails;
    }

    private static String firstDishName(Order order) {
        return order.getItems().stream()
            .min(Comparator.comparing(OrderItem::getId))
            .map(OrderItem::getDishNombre)
            .orElse(null);
    }
}
