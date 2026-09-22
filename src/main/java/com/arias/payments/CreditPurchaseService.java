package com.arias.payments;

import com.arias.common.config.PublicUrlProperties;
import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditLedgerService;
import com.arias.credits.MovementRef;
import com.arias.credits.MovementType;
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderRepository;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Compra de paquetes y compra directa de créditos vía Mercado Pago Checkout
 * Pro (unidad 11, tareas 11.3–11.5, diseño §Flujo de datos "Compra con
 * Mercado Pago"). Único punto que crea {@link CreditPurchase}, procesa la
 * notificación del webhook y aplica el mapeo de estados de Mercado Pago.
 *
 * <p><b>Compra directa — nota de deviación</b> (sin resolver por el diseño
 * original, documentada acá porque no hay una tarifa de créditos
 * independiente del catálogo de paquetes): el precio unitario de una compra
 * directa se deriva de {@code price_cents / credit_amount} del paquete
 * {@code DAY} habilitado (la denominación más chica) — el administrador
 * DEBE configurar un paquete con {@code code = "DAY"} para que la compra
 * directa esté disponible; si no existe, el endpoint responde 503 en vez de
 * adivinar un precio. Requiere confirmación de producto (ver `tasks.md`
 * unidad 11.3 y "Puntos abiertos" de `design.md`).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreditPurchaseService {

    private static final String DAY_PACK_CODE = "DAY";
    private static final String WEBHOOK_PATH = "/api/webhooks/mercadopago";

    private final CreditPurchaseRepository purchaseRepo;
    private final CreditPackRepository packRepo;
    private final OrderRepository orderRepo;
    private final UserRepository userRepo;
    private final CreditLedgerService creditLedgerService;
    private final PaymentGateway paymentGateway;
    private final PublicUrlProperties publicUrlProps;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Crea la compra {@code PENDING} e inicia el checkout. El importe se
     * calcula siempre acá, nunca lo manda el cliente (diseño §Seguridad). Si
     * Mercado Pago no está configurado, {@link PaymentGateway#createCheckout}
     * lanza 503 y esta transacción entera se revierte — no queda una compra
     * huérfana en la base.
     */
    @Transactional
    public CreditPurchaseCheckoutDto createPurchase(Long userId, CreatePurchaseRequest req) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> BusinessException.notFound("user-not-found", "Usuario no encontrado"));

        CreditPack pack = null;
        Order order = null;
        int creditAmount;
        long amountCents;

        if (req.type() == PurchaseType.PACK) {
            if (req.packId() == null) {
                throw BusinessException.badRequest("pack-id-required", "Debe indicar el paquete a comprar");
            }
            pack = packRepo.findById(req.packId())
                .filter(p -> p.getDeletedAt() == null && Boolean.TRUE.equals(p.getEnabled()))
                .orElseThrow(() -> BusinessException.notFound("credit-pack-not-found",
                    "Paquete de créditos no encontrado"));
            creditAmount = pack.getCreditAmount();
            amountCents = pack.getPriceCents();
        } else {
            if (req.orderId() == null) {
                throw BusinessException.badRequest("order-id-required",
                    "Debe indicar el pedido a pagar directamente");
            }
            order = orderRepo.findByIdAndUserId(req.orderId(), userId)
                .orElseThrow(() -> BusinessException.notFound("order-not-found", "Pedido no encontrado"));
            if (order.getEstado() != OrderEstado.PENDIENTE) {
                throw BusinessException.conflict("order-not-payable",
                    "Ese pedido ya no admite un pago directo");
            }
            creditAmount = order.getCreditTotal();
            amountCents = directAmountCentsFor(creditAmount);
        }

        CreditPurchase purchase = CreditPurchase.builder()
            .user(user)
            .type(req.type())
            .pack(pack)
            .order(order)
            .creditAmount(creditAmount)
            .amountCents(amountCents)
            .currency("ARS")
            .status(CreditPurchaseStatus.PENDING)
            .build();
        purchase = purchaseRepo.save(purchase);

        String frontendUrl = publicUrlProps.frontendUrl();
        String returnUrl = frontendUrl + "/compras/" + purchase.getId() + "/procesando";
        CheckoutRequest checkoutReq = new CheckoutRequest(
            purchase.getId().toString(),
            checkoutTitle(req.type(), creditAmount),
            1,
            amountCents,
            user.getEmail(),
            returnUrl,
            returnUrl,
            returnUrl,
            publicUrlProps.backendUrl() + WEBHOOK_PATH
        );
        CheckoutSession session = paymentGateway.createCheckout(checkoutReq);
        purchase.setMpPreferenceId(session.preferenceId());

        return new CreditPurchaseCheckoutDto(purchase.getId(), session.initPoint());
    }

    @Transactional(readOnly = true)
    public CreditPurchaseDto getPurchase(Long userId, UUID id) {
        CreditPurchase purchase = purchaseRepo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> BusinessException.notFound("purchase-not-found", "Compra no encontrada"));
        return CreditPurchaseDto.from(purchase);
    }

    /**
     * Pasos 3–9 del diseño: {@code PaymentClient.get(id)} como fuente de
     * verdad (nunca el cuerpo del webhook), resolver la compra, comparar
     * importe, bloquear fila, mapear estado, commitear. Invocado por {@link
     * MercadoPagoWebhookController} con el {@code data.id} ya validado por
     * firma. Puede lanzar (502) si Mercado Pago no responde — el controller
     * deja que se propague para que Mercado Pago reintente más tarde.
     */
    @Transactional
    public void processPaymentNotification(String paymentId) {
        applySnapshot(paymentGateway.getPayment(paymentId));
    }

    /**
     * Aplica un {@link PaymentSnapshot} ya resuelto — usado tanto por {@link
     * #processPaymentNotification} (webhook) como por {@code
     * PaymentReconciliationScheduler} (que ya lo obtuvo por
     * {@code external_reference}).
     */
    @Transactional
    public void applySnapshot(PaymentSnapshot snapshot) {
        UUID purchaseId;
        try {
            purchaseId = UUID.fromString(snapshot.externalReference());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("Notificación de Mercado Pago con external_reference no reconocible: {}",
                snapshot.externalReference());
            return;
        }

        // Paso 6: SELECT ... FOR UPDATE — bloquea la fila antes de decidir.
        CreditPurchase purchase = purchaseRepo.findByIdForUpdate(purchaseId).orElse(null);
        if (purchase == null) {
            log.warn("Notificación de Mercado Pago para una compra inexistente: {}", purchaseId);
            return;
        }

        // Paso 5: el importe SIEMPRE se compara contra lo guardado en la compra.
        if (snapshot.amountCents() != purchase.getAmountCents()
            || !purchase.getCurrency().equalsIgnoreCase(snapshot.currency())) {
            log.error("Importe/moneda del pago {} no coincide con la compra {}: pago={} {} vs compra={} {}",
                snapshot.paymentId(), purchase.getId(), snapshot.amountCents(), snapshot.currency(),
                purchase.getAmountCents(), purchase.getCurrency());
            return;
        }

        // Replay: UNIQUE(mp_payment_id) es la enforcement real; esto es la
        // comprobación de aplicación previa a intentar el UPDATE/INSERT.
        if (purchase.getMpPaymentId() == null) {
            purchase.setMpPaymentId(snapshot.paymentId());
        } else if (!purchase.getMpPaymentId().equals(snapshot.paymentId())) {
            log.error("La compra {} ya está asociada a otro payment_id ({} vs {}) — se ignora",
                purchase.getId(), purchase.getMpPaymentId(), snapshot.paymentId());
            return;
        }

        purchase.setMpStatusDetail(snapshot.statusDetail());
        applyStatusMapping(purchase, snapshot);
    }

    /** Marca una compra {@code PENDING} sin pago reportado en 24 h como {@code EXPIRED} (11.6). */
    @Transactional
    public void expirePendingPurchase(UUID purchaseId) {
        purchaseRepo.findByIdForUpdate(purchaseId).ifPresent(p -> {
            if (p.getStatus() == CreditPurchaseStatus.PENDING) {
                p.setStatus(CreditPurchaseStatus.EXPIRED);
                log.info("Compra {} expirada — 24 h sin pago reportado por Mercado Pago", purchaseId);
            }
        });
    }

    // ─── mapeo de estados (diseño §Flujo de datos, tabla "Estado del pago → Acción") ──

    private void applyStatusMapping(CreditPurchase purchase, PaymentSnapshot snapshot) {
        switch (snapshot.status()) {
            case APPROVED -> {
                if (purchase.getStatus() == CreditPurchaseStatus.PENDING) {
                    creditPurchase(purchase);
                }
                // Un reembolso PARCIAL puede dejar el pago en `approved` con
                // transaction_amount_refunded poblado (decisión del
                // orquestador, unidad 9) — se revisa siempre, no solo en la
                // rama refunded/charged_back.
                maybeReverse(purchase, snapshot);
            }
            case PENDING, IN_PROCESS, AUTHORIZED -> {
                // Sin cambios — la reconciliación horaria vuelve a mirar.
            }
            case REJECTED -> closeWithoutCrediting(purchase, CreditPurchaseStatus.REJECTED);
            case CANCELLED -> closeWithoutCrediting(purchase, CreditPurchaseStatus.CANCELLED);
            case REFUNDED, CHARGED_BACK -> maybeReverse(purchase, snapshot);
            case IN_MEDIATION -> purchase.setStatus(CreditPurchaseStatus.IN_MEDIATION);
            case UNKNOWN -> log.warn("Estado de pago desconocido de Mercado Pago para la compra {}: detail={}",
                purchase.getId(), snapshot.statusDetail());
        }
    }

    private void creditPurchase(CreditPurchase purchase) {
        MovementRef ref = MovementRef.forPurchase(purchase.getId(),
            (purchase.getType() == PurchaseType.PACK ? "Compra de paquete" : "Compra directa")
                + " #" + purchase.getId());

        if (purchase.getType() == PurchaseType.PACK) {
            // PACK_PURCHASE: +N available, renueva expires_at (CreditLedgerService.apply).
            creditLedgerService.apply(purchase.getUser().getId(), MovementType.PACK_PURCHASE,
                purchase.getCreditAmount(), 0, ref);
        } else {
            // DIRECT_PURCHASE: +N committed directo, sin pasar por available ni renovar.
            creditLedgerService.apply(purchase.getUser().getId(), MovementType.DIRECT_PURCHASE,
                0, purchase.getCreditAmount(), ref);
        }

        purchase.setStatus(CreditPurchaseStatus.APPROVED);
        purchase.setCreditedAt(clock.instant());

        eventPublisher.publishEvent(new CreditPurchaseCreditedEvent(
            purchase.getId(), purchase.getUser().getId(), purchase.getUser().getEmail(),
            purchase.getType(), purchase.getCreditAmount()));
    }

    /**
     * Nota de deviación: el diseño dice "si era compra directa, se cancela
     * el pedido asociado" al rechazar/cancelar el pago. Esta unidad NO
     * implementa esa cancelación automática — requeriría acoplar este
     * service a {@code OrderPlacementService.cancel}, que exige {@code
     * estado == PENDIENTE} y libera crédito COMMITTED que acá nunca se llegó
     * a comprometer (el pedido de una compra directa rechazada queda
     * simplemente sin pagar, PENDIENTE, tal como estaba). Documentado
     * también en {@code tasks.md} unidad 11.5 como punto abierto para
     * producto: qué debe pasar con ese pedido.
     */
    private void closeWithoutCrediting(CreditPurchase purchase, CreditPurchaseStatus status) {
        if (purchase.getStatus() != CreditPurchaseStatus.PENDING) {
            return; // ya se había resuelto (p. ej. ya acreditada) — no se cierra retroactivamente
        }
        purchase.setStatus(status);
    }

    /**
     * Reversión proporcional al reembolso ACUMULADO reportado por Mercado
     * Pago (decisión del orquestador que resuelve la unidad 9, ver
     * javadoc de {@link PaymentSnapshot}): calcula cuántos créditos
     * DEBERÍAN estar revertidos a esta altura y aplica solo el delta contra
     * {@code credit_purchase.credits_reversed} — así un reembolso parcial
     * repetido nunca revierte de más, y la cuenta nunca supera
     * {@code creditAmount}. Idempotente ante reintentos del mismo webhook.
     */
    private void maybeReverse(CreditPurchase purchase, PaymentSnapshot snapshot) {
        if (purchase.getStatus() != CreditPurchaseStatus.APPROVED
            && purchase.getStatus() != CreditPurchaseStatus.REVERSED) {
            return; // nunca se acreditó — nada que revertir
        }

        int creditAmount = purchase.getCreditAmount();
        int targetTotal;
        if (snapshot.status() == PaymentStatus.CHARGED_BACK && snapshot.amountRefundedCents() <= 0) {
            // Un contracargo no siempre reporta transaction_amount_refunded —
            // a diferencia de un reembolso, es una reversión forzada por el
            // banco del comprador: se trata como reversión total.
            targetTotal = creditAmount;
        } else if (snapshot.amountRefundedCents() <= 0) {
            return; // refunded/charged_back reportado sin monto — nada que calcular todavía
        } else {
            double ratio = purchase.getAmountCents() <= 0
                ? 1.0
                : (double) snapshot.amountRefundedCents() / (double) purchase.getAmountCents();
            ratio = Math.min(Math.max(ratio, 0.0), 1.0);
            targetTotal = Math.min(creditAmount, Math.round((float) (creditAmount * ratio)));
        }

        int delta = targetTotal - purchase.getCreditsReversed();
        if (delta <= 0) {
            return; // ya se revirtió lo que corresponde a este reembolso — evita doble reversión
        }

        MovementRef ref = MovementRef.forPurchase(purchase.getId(),
            "Reversión por reembolso de la compra #" + purchase.getId());
        creditLedgerService.reverse(purchase.getUser().getId(), delta, ref);

        purchase.setCreditsReversed(purchase.getCreditsReversed() + delta);
        purchase.setReversedAt(clock.instant());
        boolean fullyReversed = purchase.getCreditsReversed() >= creditAmount;
        if (fullyReversed) {
            purchase.setStatus(CreditPurchaseStatus.REVERSED);
        }

        eventPublisher.publishEvent(new CreditPurchaseReversedEvent(
            purchase.getId(), purchase.getUser().getId(), purchase.getUser().getEmail(),
            delta, purchase.getCreditsReversed(), creditAmount, fullyReversed));
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private long directAmountCentsFor(int creditAmount) {
        CreditPack dayPack = packRepo.findByCodeAndDeletedAtIsNullAndEnabledTrue(DAY_PACK_CODE)
            .orElseThrow(() -> new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                "direct-purchase-unavailable",
                "La compra directa no está disponible — falta configurar el paquete DAY"));
        // Redondeo hacia arriba: nunca cobrar de menos por truncamiento.
        long unitPriceCents = -Math.floorDiv(-dayPack.getPriceCents(), dayPack.getCreditAmount());
        return unitPriceCents * creditAmount;
    }

    private String checkoutTitle(PurchaseType type, int creditAmount) {
        return type == PurchaseType.PACK
            ? "Paquete de almuerzos — Arias"
            : creditAmount + " almuerzo(s) — Arias";
    }
}
