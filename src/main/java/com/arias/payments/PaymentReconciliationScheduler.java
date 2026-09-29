package com.arias.payments;

import com.arias.payments.mercadopago.MercadoPagoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reconciliación horaria de compras {@code PENDING} (unidad 11, tarea 11.6,
 * diseño §Flujo de datos "Reconciliación"): cubre el webhook perdido, que es
 * el modo de falla real del flujo. Más de 30 min sin resolución → se
 * re-consulta a Mercado Pago por {@code external_reference}; más de 24 h sin
 * ningún pago reportado → {@code EXPIRED}. Desde la unidad B15 también
 * re-consulta las compras ya cerradas sin acreditar (REJECTED, CANCELLED,
 * EXPIRED) de las últimas {@value #CLOSED_RECONCILE_HOURS} h, para acreditar un
 * reintento de pago con otra tarjeta o un pago tardío cuyo webhook se perdió.
 * El webhook sigue siendo el camino principal.
 *
 * <p>Inerte si Mercado Pago no está configurado (mismo patrón que {@code
 * MercadoPagoAdapter}): no intenta ninguna llamada de red.
 *
 * <p>Delega toda mutación a {@link CreditPurchaseService} — nunca se
 * auto-invoca un método {@code @Transactional} propio, porque el proxy de
 * Spring no intercepta llamadas dentro de la misma instancia.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationScheduler {

    private static final int PENDING_RECONCILE_MINUTES = 30;
    /** Horas que una compra {@code PENDING} sigue viva antes de expirar; también la ventana de {@code GET /purchases/pending}. */
    static final int PENDING_EXPIRE_HOURS = 24;
    /**
     * Unidad B15: ventana (desde la creación de la compra) en la que se sigue
     * consultando a Mercado Pago por compras ya cerradas sin acreditar
     * (REJECTED, CANCELLED, EXPIRED): 24 h de vida del checkout + 24 h de
     * margen para un pago tardío. Pasada la ventana sólo queda la revisión
     * manual del pago en Mercado Pago.
     */
    static final int CLOSED_RECONCILE_HOURS = 2 * PENDING_EXPIRE_HOURS;
    private static final Set<CreditPurchaseStatus> CLOSED_UNCREDITED = Set.of(
        CreditPurchaseStatus.REJECTED, CreditPurchaseStatus.CANCELLED, CreditPurchaseStatus.EXPIRED);

    private final CreditPurchaseRepository purchaseRepo;
    private final CreditPurchaseService purchaseService;
    private final PaymentGateway paymentGateway;
    private final MercadoPagoProperties mercadoPagoProps;
    private final Clock clock;

    @Scheduled(cron = "0 0 * * * *")
    public void reconcile() {
        if (!mercadoPagoProps.isConfigured()) {
            return;
        }

        Instant now = clock.instant();
        Instant reconcileCutoff = now.minus(PENDING_RECONCILE_MINUTES, ChronoUnit.MINUTES);
        Instant expireCutoff = now.minus(PENDING_EXPIRE_HOURS, ChronoUnit.HOURS);

        List<CreditPurchase> pending = purchaseRepo.findByStatusAndCreatedAtBefore(
            CreditPurchaseStatus.PENDING, reconcileCutoff);

        int reconciled = 0;
        int expired = 0;
        int failed = 0;
        for (CreditPurchase purchase : pending) {
            // Cada compra se aísla: una falla inesperada (p. ej. del SDK) no
            // puede frenar al resto, porque se repetiría en cada corrida.
            try {
                if (purchase.getCreatedAt().isBefore(expireCutoff)) {
                    purchaseService.expirePendingPurchase(purchase.getId());
                    expired++;
                    continue;
                }
                if (reconcileOne(purchase.getId())) {
                    reconciled++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("[CRON-PAYMENT-RECONCILE] Falló la compra {}; se reintenta en la próxima corrida",
                    purchase.getId(), e);
            }
        }

        // Unidad B15: el cliente pudo reintentar con otra tarjeta en el mismo checkout
        // (o pagar tarde) y perderse el webhook; applySnapshot acredita el pago aprobado
        // de una compra cerrada sin acreditar y es idempotente para el resto.
        List<CreditPurchase> closed = purchaseRepo.findByStatusInAndCreditedAtIsNullAndCreatedAtAfter(
            CLOSED_UNCREDITED, now.minus(CLOSED_RECONCILE_HOURS, ChronoUnit.HOURS));
        for (CreditPurchase purchase : closed) {
            try {
                if (reconcileOne(purchase.getId())) {
                    reconciled++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("[CRON-PAYMENT-RECONCILE] Falló la compra cerrada {}; se reintenta en la próxima corrida",
                    purchase.getId(), e);
            }
        }

        if (reconciled > 0 || expired > 0 || failed > 0) {
            log.info("[CRON-PAYMENT-RECONCILE] {} compra(s) reconciliada(s), {} expirada(s), {} fallida(s)",
                reconciled, expired, failed);
        }
    }

    /**
     * Re-consulta por {@code external_reference} FUERA de cualquier
     * transacción/lock (el llamado de red va primero, igual que el webhook)
     * y delega la mutación a {@link CreditPurchaseService#applySnapshot}.
     */
    private boolean reconcileOne(java.util.UUID purchaseId) {
        Optional<PaymentSnapshot> snapshot = paymentGateway.findByExternalReference(purchaseId.toString());
        if (snapshot.isEmpty()) {
            return false; // sin pago reportado todavía — se reintenta en la próxima corrida, hasta las 24 h
        }
        purchaseService.applySnapshot(snapshot.get());
        return true;
    }
}
