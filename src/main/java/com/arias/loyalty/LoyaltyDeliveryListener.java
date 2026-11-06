package com.arias.loyalty;

import com.arias.orders.OrderDeliveredEvent;
import com.arias.orders.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accrues loyalty once the kitchen delivery has committed, one transaction per order, so a loyalty
 * failure can never undo or block the delivery. A missed accrual self-heals: milestones are derived
 * from the delivered orders and granted on the next delivery or the next {@code GET /api/v1/loyalty}.
 */
@Slf4j
@Component
public class LoyaltyDeliveryListener {

    private final LoyaltyService loyaltyService;
    private final OrderRepository orderRepo;
    private final TransactionTemplate tx;

    public LoyaltyDeliveryListener(LoyaltyService loyaltyService, OrderRepository orderRepo,
                                   PlatformTransactionManager txManager) {
        this.loyaltyService = loyaltyService;
        this.orderRepo = orderRepo;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDelivered(OrderDeliveredEvent event) {
        for (Long orderId : event.orderIds()) {
            try {
                tx.executeWithoutResult(s -> orderRepo.findById(orderId).ifPresent(loyaltyService::onDelivered));
            } catch (RuntimeException e) {
                log.warn("[LOYALTY] accrual of order #{} failed; it will be recovered on the next delivery or view",
                    orderId, e);
            }
        }
    }
}
