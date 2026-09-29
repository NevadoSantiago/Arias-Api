package com.arias.payments;

import com.arias.payments.mercadopago.MercadoPagoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
 * <p>Unidad B15.1: la pasada de compras cerradas está acotada. Consulta cada
 * compra sólo en unos pocos puntos de control desde su creación ({@link
 * #CLOSED_CHECKPOINT_HOURS}: +1 h, +6 h, +24 h, +47 h) en vez de una vez por
 * hora, y como máximo {@code arias.payments.reconcile.closed-max-per-run}
 * compras por corrida (las más viejas primero, por ser las más cercanas al fin
 * de la ventana). Sin columna nueva: el calendario sale de {@code createdAt}.
 * Una compra diferida por el tope pierde sólo ese punto de control; quedan los
 * siguientes, y la diferida se reporta en el resumen.
 *
 * <p>Con varios pagos para una misma compra (uno rechazado y un reintento
 * aprobado) se aplica el aprobado si lo hay, y si no el más reciente.
 *
 * <p>Inerte si Mercado Pago no está configurado (mismo patrón que {@code
 * MercadoPagoAdapter}): no intenta ninguna llamada de red.
 *
 * <p>Delega toda mutación a {@link CreditPurchaseService} — nunca se
 * auto-invoca un método {@code @Transactional} propio, porque el proxy de
 * Spring no intercepta llamadas dentro de la misma instancia.
 */
@Component
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
    /**
     * Unidad B15.1: horas desde la creación en las que se vuelve a consultar una
     * compra cerrada sin acreditar. Un reintento o un pago tardío suele llegar
     * pronto o al vencer el checkout; el último punto queda antes del fin de
     * {@link #CLOSED_RECONCILE_HOURS}.
     */
    static final List<Integer> CLOSED_CHECKPOINT_HOURS = List.of(1, 6, 24, 47);
    /** La reconciliación corre cada hora: un punto de control cae en exactamente una corrida. */
    private static final Duration RUN_INTERVAL = Duration.ofHours(1);
    private static final Set<CreditPurchaseStatus> CLOSED_UNCREDITED = Set.of(
        CreditPurchaseStatus.REJECTED, CreditPurchaseStatus.CANCELLED, CreditPurchaseStatus.EXPIRED);

    private final CreditPurchaseRepository purchaseRepo;
    private final CreditPurchaseService purchaseService;
    private final PaymentGateway paymentGateway;
    private final MercadoPagoProperties mercadoPagoProps;
    private final Clock clock;
    private final int closedMaxPerRun;

    public PaymentReconciliationScheduler(
        CreditPurchaseRepository purchaseRepo,
        CreditPurchaseService purchaseService,
        PaymentGateway paymentGateway,
        MercadoPagoProperties mercadoPagoProps,
        Clock clock,
        @Value("${arias.payments.reconcile.closed-max-per-run:50}") int closedMaxPerRun
    ) {
        this.purchaseRepo = purchaseRepo;
        this.purchaseService = purchaseService;
        this.paymentGateway = paymentGateway;
        this.mercadoPagoProps = mercadoPagoProps;
        this.clock = clock;
        this.closedMaxPerRun = Math.max(1, closedMaxPerRun);
    }

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

        if (reconciled > 0 || expired > 0 || failed > 0) {
            log.info("[CRON-PAYMENT-RECONCILE] {} compra(s) reconciliada(s), {} expirada(s), {} fallida(s)",
                reconciled, expired, failed);
        }

        reconcileClosedPurchases(now);
    }

    /**
     * Unidad B15: el cliente pudo reintentar con otra tarjeta en el mismo checkout
     * (o pagar tarde) y perderse el webhook; applySnapshot acredita el pago aprobado
     * de una compra cerrada sin acreditar y es idempotente para el resto. Unidad
     * B15.1: sólo las compras en un punto de control, con tope por corrida, con
     * contadores propios y una falla aislada por compra.
     */
    private void reconcileClosedPurchases(Instant now) {
        List<CreditPurchase> due = purchaseRepo.findByStatusInAndCreditedAtIsNullAndCreatedAtAfter(
                CLOSED_UNCREDITED, now.minus(CLOSED_RECONCILE_HOURS, ChronoUnit.HOURS)).stream()
            .filter(p -> isClosedCheckDue(p.getCreatedAt(), now))
            .sorted(Comparator.comparing(CreditPurchase::getCreatedAt))
            .toList();
        if (due.isEmpty()) {
            return;
        }

        int consulted = 0;
        int approvedApplied = 0;
        int failed = 0;
        for (CreditPurchase purchase : due.subList(0, Math.min(due.size(), closedMaxPerRun))) {
            consulted++;
            try {
                PaymentSnapshot snapshot = lookupBestSnapshot(purchase.getId());
                if (snapshot == null) {
                    continue;
                }
                purchaseService.applySnapshot(snapshot);
                if (snapshot.status() == PaymentStatus.APPROVED) {
                    approvedApplied++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("[CRON-PAYMENT-RECONCILE] Falló la compra cerrada {}; se reintenta en su próximo punto de control",
                    purchase.getId(), e);
            }
        }
        int deferred = due.size() - consulted;
        log.info("[CRON-PAYMENT-RECONCILE] compras cerradas: {} consultada(s), {} con pago aprobado aplicado "
                + "(posible acreditación tardía), {} fallida(s), {} diferida(s) por el tope de {} por corrida",
            consulted, approvedApplied, failed, deferred, closedMaxPerRun);
    }

    /**
     * ¿Toca consultar esta compra cerrada en esta corrida? Sí si algún punto de
     * control ({@link #CLOSED_CHECKPOINT_HOURS} desde la creación) cayó dentro de
     * la última hora.
     */
    static boolean isClosedCheckDue(Instant createdAt, Instant now) {
        Duration age = Duration.between(createdAt, now);
        for (int hours : CLOSED_CHECKPOINT_HOURS) {
            Duration sinceCheckpoint = age.minusHours(hours);
            if (!sinceCheckpoint.isNegative() && sinceCheckpoint.compareTo(RUN_INTERVAL) < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Re-consulta por {@code external_reference} FUERA de cualquier
     * transacción/lock (el llamado de red va primero, igual que el webhook)
     * y delega la mutación a {@link CreditPurchaseService#applySnapshot}.
     */
    private boolean reconcileOne(UUID purchaseId) {
        PaymentSnapshot snapshot = lookupBestSnapshot(purchaseId);
        if (snapshot == null) {
            return false; // sin pago reportado todavía — se reintenta en la próxima corrida, hasta las 24 h
        }
        purchaseService.applySnapshot(snapshot);
        return true;
    }

    /**
     * Unidad B15.1: entre todos los pagos de la compra prefiere uno aprobado
     * (el reintento con otra tarjeta puede venir detrás de un rechazo, en
     * cualquier orden); si no hay ninguno aprobado, el más reciente. La
     * pasarela los devuelve del más reciente al más viejo. {@code null} si no
     * hay ningún pago.
     */
    private PaymentSnapshot lookupBestSnapshot(UUID purchaseId) {
        List<PaymentSnapshot> snapshots = paymentGateway.findAllByExternalReference(purchaseId.toString());
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        return snapshots.stream()
            .filter(s -> s.status() == PaymentStatus.APPROVED)
            .findFirst()
            .orElse(snapshots.get(0));
    }
}
