package com.arias.payments;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Confirma una compra {@code PENDING} cuando el cliente vuelve del checkout,
 * sin esperar al webhook ni a la reconciliación horaria. Este servicio NO es
 * {@code @Transactional}: la consulta a Mercado Pago va fuera de toda
 * transacción (igual que el webhook y el scheduler) y la mutación la hace
 * {@link CreditPurchaseService#applySnapshot}, idempotente por su bloqueo de
 * fila y su guarda de estado. Un error de Mercado Pago nunca rompe el pedido.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseConfirmationService {

    private final CreditPurchaseService purchaseService;
    private final PaymentGateway paymentGateway;
    private final PurchaseConfirmThrottle throttle;

    public CreditPurchaseDto confirm(Long userId, UUID purchaseId) {
        // También verifica la propiedad: 404 si la compra no es del usuario, sin tocar Mercado Pago.
        CreditPurchaseDto current = purchaseService.getPurchase(userId, purchaseId);
        if (current.status() != CreditPurchaseStatus.PENDING || !throttle.tryAcquire(purchaseId)) {
            return current;
        }
        try {
            PaymentSnapshot snapshot = PaymentSnapshotSelector.best(
                paymentGateway.findRecentByExternalReference(purchaseId.toString()));
            if (snapshot == null) {
                return current;
            }
            purchaseService.applySnapshot(snapshot);
        } catch (RuntimeException e) {
            log.warn("Could not confirm purchase {} with Mercado Pago: {}", purchaseId, e.toString());
            return current;
        }
        return purchaseService.getPurchase(userId, purchaseId);
    }
}
