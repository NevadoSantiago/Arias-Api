package com.arias.payments;

import com.arias.common.config.PublicUrlProperties;
import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditLedgerService;
import com.arias.credits.MovementRef;
import com.arias.credits.MovementType;
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.credits.packs.CreditPackType;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderPlacementService;
import com.arias.orders.OrderRepository;
import com.arias.orders.PlaceOrderV2Request;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * {@code INDIVIDUAL} habilitado (la denominación más chica) — el administrador
 * DEBE configurar un paquete con {@code packType = INDIVIDUAL} para que la compra
 * directa esté disponible; si no existe, el endpoint responde 503 en vez de
 * adivinar un precio. Requiere confirmación de producto (ver `tasks.md`
 * unidad 11.3 y "Puntos abiertos" de `design.md`).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreditPurchaseService {

    private static final String WEBHOOK_PATH = "/api/webhooks/mercadopago";

    private final CreditPurchaseRepository purchaseRepo;
    private final CreditPackRepository packRepo;
    private final OrderRepository orderRepo;
    private final UserRepository userRepo;
    private final CreditLedgerService creditLedgerService;
    private final OrderPlacementService orderPlacementService;
    private final PaymentGateway paymentGateway;
    private final PublicUrlProperties publicUrlProps;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    /**
     * Fases transaccionales cortas de los checkouts (unidad B13.1). Se usa en
     * vez de {@code @Transactional} porque la llamada a Mercado Pago tiene que
     * quedar FUERA de toda transacción, y la auto-invocación de un método
     * {@code @Transactional} de esta misma clase no pasa por el proxy (mismo
     * motivo que documenta {@code PaymentReconciliationScheduler}).
     */
    private final TransactionTemplate txTemplate;

    /**
     * Crea la compra {@code PENDING} e inicia el checkout. El importe se
     * calcula siempre acá, nunca lo manda el cliente (diseño §Seguridad).
     *
     * <p><b>Tres pasos, con la llamada de red fuera de toda transacción</b>
     * (unidad B13.1; ver {@link #createDirectCheckout} para el razonamiento
     * completo): (1) transacción corta que valida e inserta la compra
     * {@code PENDING}; (2) {@link PaymentGateway#createCheckout} SIN
     * transacción — no retiene ninguna conexión del pool mientras Mercado Pago
     * responde; (3) transacción corta que guarda el {@code preferenceId}. Si
     * el paso 2 falla (503 si no está configurado, 502 si Mercado Pago
     * rechaza o no responde) la compra queda {@code CANCELLED} ("nunca llegó
     * al proveedor"; ya no se revierte a la nada porque el insert está
     * commiteado) y se relanza el MISMO error.
     *
     * <p><b>{@code type = DIRECT} ya NO se admite acá</b> (unidad B7, cierre
     * del camino viejo): antes exigía un pedido {@code PENDIENTE} ya creado,
     * pero crear ese pedido con {@code OrderPlacementService.place()} YA
     * comprometía créditos del saldo — pagarlo "directo" después cobraba dos
     * veces (bug verificado: {@code committed} pasaba de 2 a 4). El pago
     * directo ahora nace ATADO a su propio pedido, sin comprometer saldo, en
     * {@link #createDirectCheckout}.
     */
    public CreditPurchaseCheckoutDto createPurchase(Long userId, CreatePurchaseRequest req) {
        if (req.type() == PurchaseType.DIRECT) {
            throw BusinessException.badRequest("direct-purchase-not-supported",
                "La compra directa ya no se paga acá — pagá el pedido directamente desde su checkout.");
        }

        // Paso 0 — fail fast (B13.2): con Mercado Pago apagado, 503 sin crear ninguna fila.
        paymentGateway.requireAvailable();

        // Paso 1 — transacción corta: valida e inserta la compra PENDING.
        PendingPackPurchase pending = txTemplate.execute(status -> insertPendingPackPurchase(userId, req));

        // Paso 2 — SIN transacción: llamada de red a Mercado Pago.
        CheckoutSession session = createCheckoutOrCancel(pending.purchaseId(), pending.checkoutRequest());

        // Paso 3 — transacción corta: guarda el preferenceId (si falla, compensa).
        attachCheckoutOrCancel(pending.purchaseId(), session, false);

        return new CreditPurchaseCheckoutDto(pending.purchaseId(), session.initPoint());
    }

    /** Datos de la fase 1 de {@link #createPurchase}, ya sin entidades (viven fuera de la transacción). */
    private record PendingPackPurchase(UUID purchaseId, CheckoutRequest checkoutRequest) {}

    private PendingPackPurchase insertPendingPackPurchase(Long userId, CreatePurchaseRequest req) {

        User user = userRepo.findById(userId)
            .orElseThrow(() -> BusinessException.notFound("user-not-found", "Usuario no encontrado"));

        // Mismo gate que OrderPlacementService.place() — ver diseño
        // §Seguridad, "Cuentas sin verificar", y User.mustVerifyEmailToSpend
        // para la exención de empleados de empresa.
        if (user.mustVerifyEmailToSpend()) {
            throw BusinessException.conflict("email-not-verified",
                "Debés verificar tu correo electrónico antes de comprar créditos");
        }

        // Mismo gate que OrderPlacementService.place() — ver diseño
        // §Decisión 9 y User.mustCompleteProfileToSpend para la exención de
        // empleados de empresa. Va después del gate de email por la misma
        // razón: verificar identidad primero, pedir datos de perfil después.
        if (user.mustCompleteProfileToSpend()) {
            throw BusinessException.conflict("profile-incomplete",
                "Completá tu teléfono y apodo antes de comprar créditos");
        }

        if (req.packId() == null) {
            throw BusinessException.badRequest("pack-id-required", "Debe indicar el paquete a comprar");
        }
        CreditPack pack = packRepo.findById(req.packId())
            .filter(p -> p.getDeletedAt() == null && Boolean.TRUE.equals(p.getEnabled()))
            .orElseThrow(() -> BusinessException.notFound("credit-pack-not-found",
                "Paquete de créditos no encontrado"));
        // Sueltos sobre el pack INDIVIDUAL (decisión de usuario 2026-09-25): quantity
        // opcional (1..10, validado en CreatePurchaseRequest); null equivale a 1.
        int quantity = req.quantity() != null ? req.quantity() : 1;
        int creditAmount = pack.getCreditAmount() * quantity;
        long amountCents = pack.getPriceCents() * quantity;
        // La línea de Mercado Pago lleva quantity * precio unitario del
        // paquete, para que el total que ve Mercado Pago coincida siempre
        // con amountCents calculado acá — nunca un importe único inventado.
        long mpUnitPriceCents = pack.getPriceCents();

        CreditPurchase purchase = CreditPurchase.builder()
            .user(user)
            .type(PurchaseType.PACK)
            .pack(pack)
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
            checkoutTitle(PurchaseType.PACK, creditAmount),
            quantity,
            mpUnitPriceCents,
            user.getEmail(),
            returnUrl,
            returnUrl,
            returnUrl,
            publicUrlProps.backendUrl() + WEBHOOK_PATH
        );
        return new PendingPackPurchase(purchase.getId(), checkoutReq);
    }

    /**
     * "Pagá este pedido directo con Mercado Pago" (unidad B7, {@code POST
     * /api/v2/orders/direct-checkout}) — reemplaza al camino DIRECT viejo
     * cerrado en {@link #createPurchase}.
     *
     * <p><b>Tres fases; la llamada de red va FUERA de toda transacción</b>
     * (unidad B13.1). Antes todo esto era una sola transacción: mientras
     * Mercado Pago respondía seguían tomados los locks de las filas de stock
     * de los platos y de la billetera, y una conexión del pool (10, o 5 en un
     * perfil) — un Mercado Pago lento bloqueaba a los demás clientes que
     * pedían el mismo plato y podía agotar el pool. Ahora:
     * <ol>
     *   <li><b>Fase 1 (transacción corta, se commitea ANTES de la red):</b>
     *       busca el paquete {@code INDIVIDUAL} (fail fast: 503 sin haber tocado
     *       {@code orders}/stock), crea el pedido en {@link
     *       OrderEstado#PENDIENTE_PAGO} reservando stock y los almuerzos
     *       disponibles del saldo ({@link
     *       OrderPlacementService#placeAwaitingPayment}, B13), calcula el
     *       importe (precio del {@code INDIVIDUAL} × créditos que el saldo NO cubrió)
     *       e inserta la compra {@code PENDING} sin preferencia. Al commitear
     *       se sueltan todos los locks.</li>
     *   <li><b>Fase 2 (SIN transacción):</b> {@link
     *       PaymentGateway#createCheckout}.</li>
     *   <li><b>Fase 3 (transacción corta):</b> relee la compra bajo lock y
     *       guarda {@code mpPreferenceId} e {@code initPoint} ({@link
     *       #attachCheckout}). Si la compra ya no está {@code PENDING} (otro
     *       camino la cerró mientras Mercado Pago respondía: la reconciliación
     *       la expiró, el corte canceló el pedido) NO se resucita: no se
     *       guarda la preferencia, no se devuelve el {@code initPoint} al
     *       cliente y se responde 409 {@code checkout-purchase-closed}. Como
     *       el cliente nunca recibe esa URL, no puede pagar una preferencia
     *       huérfana.</li>
     * </ol>
     *
     * <p><b>Compensación si la fase 2 falla</b> (Mercado Pago rechazó, 503 sin
     * configurar, timeout del SDK; cualquier {@link RuntimeException}): en una
     * transacción nueva ({@code cancelUnreachedPurchase}) la compra pasa a
     * {@link CreditPurchaseStatus#CANCELLED} ("cancelado antes de completarse,
     * nunca se acreditó", el estado existente que mejor describe "nunca llegó
     * al proveedor"; ni el webhook ni la reconciliación tratan distinto a las
     * {@code CANCELLED}) y, como cualquier pago fallido, se cierra el pedido
     * con {@link OrderPlacementService#closeForPaymentFailure} (restaura
     * stock, libera {@code creditsFromBalance}; idempotente bajo el lock del
     * pedido). Después se relanza la excepción ORIGINAL, así que el cliente
     * recibe el mismo error que antes (p. ej. 502
     * {@code mercadopago-checkout-failed}). Si la compensación misma falla se
     * loguea y se relanza igualmente el error original: el pedido queda para
     * la limpieza de abajo.
     *
     * <p><b>Ventana de caída entre las fases 1 y 3</b> (el proceso muere, o la
     * fase 3 falla, con el pedido y la compra ya commiteados): queda un pedido
     * {@code PENDIENTE_PAGO} con una compra {@code PENDING} sin {@code
     * initPoint}. La limpieza existente lo cubre sin código nuevo: (a)
     * {@code OrderConsumptionScheduler} cancela el pedido en el corte
     * ({@code closeAtCutoff}: restaura stock, libera el saldo, avisa por mail)
     * sin mirar la compra; (b) la reconciliación consulta a Mercado Pago por
     * {@code external_reference} y, sin pago reportado, expira la compra a las
     * 24 h ({@link #expirePendingPurchase}, no-op sobre el pedido que el corte
     * ya cerró); (c) {@link #resumeDirectCheckout} exige {@code initPoint}, así
     * que responde 409 {@code direct-checkout-not-resumable} en vez de romper.
     * Si el cliente reintenta, crea un pedido nuevo.
     *
     * <p>Este método NO es {@code @Transactional}: cada fase usa un {@link
     * TransactionTemplate}. Orden de locks donde se combinan (B7.1): compra →
     * pedido → billetera.
     */
    public DirectCheckoutDto createDirectCheckout(Long userId, PlaceOrderV2Request req) {
        // Fase 0 — fail fast (B13.2): con Mercado Pago apagado, 503 sin crear pedido,
        // compra ni reservar stock/saldo (antes se creaba y se cancelaba cada intento).
        paymentGateway.requireAvailable();

        // Fase 1 — transacción corta, commiteada antes de tocar la red.
        PendingDirectPurchase pending = txTemplate.execute(status -> insertPendingDirectPurchase(userId, req));

        // Fase 2 — SIN transacción. Si falla: compensa y relanza el error original.
        CheckoutSession session = createCheckoutOrCancel(pending.purchaseId(), pending.checkoutRequest());

        // Fase 3 — transacción corta: guarda preferencia e initPoint (persistido
        // para poder devolverlo de nuevo sin crear un segundo cobro si el
        // cliente abandona Mercado Pago sin pagar — ver #resumeDirectCheckout).
        // Si falla, compensa (ver #attachCheckoutOrCancel).
        attachCheckoutOrCancel(pending.purchaseId(), session, true);

        return new DirectCheckoutDto(pending.orderId(), pending.purchaseId(), session.initPoint());
    }

    /** Datos de la fase 1 de {@link #createDirectCheckout}, ya sin entidades (viven fuera de la transacción). */
    private record PendingDirectPurchase(Long orderId, UUID purchaseId, CheckoutRequest checkoutRequest) {}

    private PendingDirectPurchase insertPendingDirectPurchase(Long userId, PlaceOrderV2Request req) {
        CreditPack dayPack = findEnabledDayPackOrThrow();

        Order order = orderPlacementService.placeAwaitingPayment(userId, req);
        User user = order.getUser();

        // Pago parcial (unidad B13): Mercado Pago cobra SOLO lo que el saldo
        // del cliente no cubrió; placeAwaitingPayment ya reservó el resto.
        int creditAmount = order.getCreditTotal() - order.getCreditsFromBalance();
        long amountCents = unitPriceCentsFor(dayPack) * creditAmount;

        CreditPurchase purchase = CreditPurchase.builder()
            .user(user)
            .type(PurchaseType.DIRECT)
            .order(order)
            .creditAmount(creditAmount)
            .amountCents(amountCents)
            .currency("ARS")
            .status(CreditPurchaseStatus.PENDING)
            .build();
        purchase = purchaseRepo.save(purchase);

        String returnUrl = publicUrlProps.frontendUrl() + "/compras/" + purchase.getId() + "/procesando";
        CheckoutRequest checkoutReq = new CheckoutRequest(
            purchase.getId().toString(),
            checkoutTitle(PurchaseType.DIRECT, creditAmount),
            1,
            amountCents,
            user.getEmail(),
            returnUrl,
            returnUrl,
            returnUrl,
            publicUrlProps.backendUrl() + WEBHOOK_PATH
        );
        return new PendingDirectPurchase(order.getId(), purchase.getId(), checkoutReq);
    }

    /**
     * Fase 2 compartida por PACK y DIRECT: llama al gateway SIN transacción y,
     * si falla, cancela la compra (y el pedido si es DIRECT) en una
     * transacción nueva antes de relanzar la excepción original.
     */
    private CheckoutSession createCheckoutOrCancel(UUID purchaseId, CheckoutRequest checkoutReq) {
        try {
            return paymentGateway.createCheckout(checkoutReq);
        } catch (RuntimeException e) {
            compensateUnreachedPurchase(purchaseId, e, true);
            throw e;
        }
    }

    /**
     * Fase 3 compartida por PACK y DIRECT (unidad B13.2): guarda la preferencia
     * en una transacción corta. Si falla al guardar (error de base, lock, pool
     * agotado) Mercado Pago ya creó una preferencia que el cliente nunca
     * recibirá: se compensa igual que en la fase 2 (compra {@code CANCELLED} +
     * cierre del pedido, idempotente, en una transacción nueva) y se lanza 503
     * {@code checkout-save-failed} conservando la original como causa. Un
     * {@link BusinessException} (p. ej. el 409 {@code checkout-purchase-closed},
     * que {@link #attachCheckout} lanza sin cambiar nada porque la compra ya
     * estaba cerrada) ya es claro y se relanza tal cual, sin compensar.
     *
     * <p><b>Preferencia viva (unidad B15):</b> si el cliente llegara a pagar esa
     * preferencia (no tiene la URL, pero Mercado Pago la creó), el webhook / la
     * reconciliación encuentran una compra {@code CANCELLED} y
     * {@link #applyStatusMapping} acredita el pago aprobado en disponibles
     * ({@link #creditLateApprovedPurchase}). Por eso el mensaje no promete que
     * "no se cobró nada".
     */
    private void attachCheckoutOrCancel(UUID purchaseId, CheckoutSession session, boolean persistInitPoint) {
        try {
            txTemplate.executeWithoutResult(status -> attachCheckout(purchaseId, session, persistInitPoint));
        } catch (BusinessException e) {
            // Ya es un error claro para el cliente (p. ej. 409 checkout-purchase-closed): la
            // compra ya estaba cerrada y attachCheckout no cambió nada, así que no hay nada
            // que compensar. Se relanza tal cual.
            throw e;
        } catch (RuntimeException e) {
            BusinessException toThrow = new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                "checkout-save-failed",
                "No pudimos completar el pago. Si llegaste a pagar, te acreditamos los almuerzos en tu saldo.");
            toThrow.initCause(e);
            log.error("Mercado Pago creó la preferencia de la compra {} pero no se pudo guardar la "
                + "preferencia en la base; se cancela la compra (la preferencia queda viva en Mercado Pago "
                + "y, si se paga, el pago aprobado se acredita en disponibles)", purchaseId, e);
            compensateUnreachedPurchase(purchaseId, toThrow, false);
            throw toThrow;
        }
    }

    /**
     * Compensación compartida: en una transacción nueva cancela la compra y
     * cierra el pedido. Si la compensación misma falla se loguea y se agrega
     * como suprimida a {@code original}, que el llamador relanza igualmente: el
     * pedido queda para la limpieza por corte/reconciliación.
     *
     * @param gatewayFailed {@code true} si Mercado Pago no creó el checkout;
     *                      {@code false} si lo creó pero no se pudo guardar
     */
    private void compensateUnreachedPurchase(UUID purchaseId, RuntimeException original, boolean gatewayFailed) {
        try {
            txTemplate.executeWithoutResult(status -> cancelUnreachedPurchase(purchaseId, gatewayFailed));
        } catch (RuntimeException compensationFailure) {
            log.error("No se pudo cancelar la compra {} ni cerrar su pedido tras {}; "
                + "la limpieza por corte/reconciliación la resuelve", purchaseId,
                gatewayFailed ? "fallar la creación del checkout en Mercado Pago"
                    : "fallar el guardado de la preferencia", compensationFailure);
            original.addSuppressed(compensationFailure);
        }
    }

    /**
     * Compensación: la compra nunca llegó a manos del cliente. Bloquea la
     * compra primero (orden compra → pedido → billetera de B7.1) y reutiliza
     * {@link #closeWithoutCrediting}, que sólo actúa si sigue {@code PENDING} y
     * cierra el pedido asociado por {@code closeForPaymentFailure}.
     */
    private void cancelUnreachedPurchase(UUID purchaseId, boolean gatewayFailed) {
        purchaseRepo.findByIdForUpdate(purchaseId).ifPresent(p -> {
            if (p.getStatus() != CreditPurchaseStatus.PENDING) {
                log.info("Compra {} ya estaba {}; la compensación del checkout no cambió nada",
                    purchaseId, p.getStatus());
                return;
            }
            closeWithoutCrediting(p, CreditPurchaseStatus.CANCELLED);
            if (gatewayFailed) {
                log.warn("Compra {} cancelada: Mercado Pago no creó el checkout", purchaseId);
            } else {
                log.warn("Compra {} cancelada: Mercado Pago sí creó la preferencia pero no se pudo guardar; "
                    + "si el cliente llegara a pagarla, el pago aprobado se acredita en disponibles", purchaseId);
            }
        });
    }

    /**
     * Fase 3 compartida: guarda la preferencia bajo lock de la compra. Sólo si
     * sigue {@code PENDING} — ver {@link #createDirectCheckout}.
     */
    private void attachCheckout(UUID purchaseId, CheckoutSession session, boolean persistInitPoint) {
        CreditPurchase purchase = purchaseRepo.findByIdForUpdate(purchaseId)
            .orElseThrow(() -> BusinessException.notFound("purchase-not-found", "Compra no encontrada"));
        if (purchase.getStatus() != CreditPurchaseStatus.PENDING) {
            log.warn("Compra {} ya no está PENDING ({}) al guardar su checkout; no se resucita",
                purchaseId, purchase.getStatus());
            throw BusinessException.conflict("checkout-purchase-closed",
                "Este pago ya no está disponible — volvé a intentarlo");
        }
        purchase.setMpPreferenceId(session.preferenceId());
        if (persistInitPoint) {
            purchase.setInitPoint(session.initPoint());
        }
    }

    /**
     * Retoma un pago directo abandonado (unidad B7, pedido del usuario: "si
     * el cliente cierra Mercado Pago sin pagar, necesita un 'Pagar ahora'") —
     * {@code GET /api/v2/orders/{id}/direct-checkout}. NUNCA crea una compra
     * ni un checkout nuevo: devuelve el {@code initPoint} ya persistido de la
     * compra DIRECT {@code PENDING} de ese pedido. Solo el dueño del pedido
     * (scope por {@code userId}, mismo patrón que el resto de {@code
     * OrderRepository}); {@code order-not-found} si no es suyo, igual que los
     * demás endpoints v2.
     */
    @Transactional(readOnly = true)
    public DirectCheckoutDto resumeDirectCheckout(Long userId, Long orderId) {
        Order order = orderRepo.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> BusinessException.notFound("order-not-found", "Pedido no encontrado"));

        if (order.getEstado() != OrderEstado.PENDIENTE_PAGO) {
            throw BusinessException.conflict("order-not-awaiting-payment",
                "Este pedido no está esperando pago");
        }

        CreditPurchase purchase = purchaseRepo.findByOrderIdAndType(orderId, PurchaseType.DIRECT)
            .filter(p -> p.getStatus() == CreditPurchaseStatus.PENDING && p.getInitPoint() != null)
            .orElseThrow(() -> BusinessException.conflict("direct-checkout-not-resumable",
                "No hay un pago directo pendiente para retomar en este pedido"));

        return new DirectCheckoutDto(order.getId(), purchase.getId(), purchase.getInitPoint());
    }

    @Transactional(readOnly = true)
    public CreditPurchaseDto getPurchase(Long userId, UUID id) {
        CreditPurchase purchase = purchaseRepo.findDetailByIdAndUserId(id, userId)
            .orElseThrow(() -> BusinessException.notFound("purchase-not-found", "Compra no encontrada"));
        return CreditPurchaseDto.from(purchase);
    }

    /**
     * Compras {@code PENDING} del usuario que siguen vivas — creadas dentro de
     * las últimas {@link PaymentReconciliationScheduler#PENDING_EXPIRE_HOURS}
     * horas, la misma ventana tras la cual la reconciliación las expira —,
     * la más nueva primero (unidad B14, aviso de pago pendiente en "Mis
     * almuerzos"). Scope por {@code userId}: nunca devuelve compras ajenas.
     */
    @Transactional(readOnly = true)
    public List<CreditPurchaseDto> listPendingPurchases(Long userId) {
        Instant since = clock.instant().minus(PaymentReconciliationScheduler.PENDING_EXPIRE_HOURS, ChronoUnit.HOURS);
        return purchaseRepo.findAlivePendingByUser(userId, since).stream()
            .map(CreditPurchaseDto::from)
            .toList();
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
        } else if (!purchase.getMpPaymentId().equals(snapshot.paymentId())
            && !adoptDifferentPaymentId(purchase, snapshot)) {
            return;
        }

        purchase.setMpStatusDetail(snapshot.statusDetail());
        applyStatusMapping(purchase, snapshot);
    }

    /**
     * Un checkout de Mercado Pago admite varios intentos de pago con la misma
     * {@code external_reference} (una tarjeta rechazada y un reintento con otra
     * en la misma preferencia): cada intento trae su propio {@code payment_id}
     * (unidad B15). Decide qué hacer con un pago cuyo id difiere del guardado.
     *
     * <ul>
     *   <li>Aprobado y la compra <b>nunca se acreditó</b> ({@code creditedAt ==
     *       null}) y está {@code PENDING} o cerrada (REJECTED, CANCELLED,
     *       EXPIRED): es EL pago que hay que acreditar — el id guardado era el
     *       de un intento fallido. Se adopta el nuevo id (el anterior queda en
     *       el log) y el procesamiento sigue.</li>
     *   <li>Aprobado y la compra <b>ya se acreditó</b>: el cliente pagó dos
     *       veces. NO se acredita otra vez; se loguea un WARN para reembolsar a
     *       mano el segundo pago en Mercado Pago.</li>
     *   <li>Cualquier otro estado del pago (rechazo de otro intento, pendiente,
     *       etc.): no es el pago que respalda esta compra; se ignora.</li>
     *   <li>Aprobado, la compra nunca se acreditó pero <b>no está PENDING ni
     *       cerrada</b> (en la práctica {@code IN_MEDIATION}: el pago guardado
     *       está en disputa): no se adopta el otro pago ni se acredita; se
     *       loguea un WARN para que alguien lo revise a mano en Mercado Pago.
     *       Si la disputa se resuelve a favor del comprador, el id guardado
     *       vuelve como {@code approved} y ese camino sí acredita.</li>
     * </ul>
     *
     * @return {@code true} si hay que seguir procesando el snapshot (id adoptado)
     */
    private boolean adoptDifferentPaymentId(CreditPurchase purchase, PaymentSnapshot snapshot) {
        String knownPaymentId = purchase.getMpPaymentId();
        if (snapshot.status() != PaymentStatus.APPROVED) {
            log.info("Pago {} de otro intento sobre la compra {} (guardado: {}, estado {}) — se ignora",
                snapshot.paymentId(), purchase.getId(), knownPaymentId, snapshot.status());
            return false;
        }
        if (purchase.getCreditedAt() != null) {
            log.warn("Pago doble: el pago aprobado {} llegó para la compra {}, que ya se acreditó con el "
                + "pago {} — NO se acredita de nuevo; hay que gestionar el reembolso manual del pago {} "
                + "en Mercado Pago", snapshot.paymentId(), purchase.getId(), knownPaymentId,
                snapshot.paymentId());
            return false;
        }
        if (purchase.getStatus() == CreditPurchaseStatus.PENDING || isClosedWithoutCredit(purchase)) {
            log.info("La compra {} ({}) adopta el pago aprobado {} en lugar del pago {}",
                purchase.getId(), purchase.getStatus(), snapshot.paymentId(), knownPaymentId);
            purchase.setMpPaymentId(snapshot.paymentId());
            return true;
        }
        log.error("El pago aprobado {} llegó para la compra {}, que sigue sin acreditar en estado {} con el pago "
            + "{} (p. ej. en mediación) — NO se adopta ni se acredita; revisar a mano en Mercado Pago",
            snapshot.paymentId(), purchase.getId(), purchase.getStatus(), knownPaymentId);
        return false;
    }

    /**
     * Cerrada sin pago (REJECTED, CANCELLED o EXPIRED) y nunca acreditada. Revisa
     * {@code creditedAt} aunque ningún camino del código deje una compra acreditada
     * en estos estados: un cambio manual no debe permitir acreditarla dos veces.
     */
    private static boolean isClosedWithoutCredit(CreditPurchase purchase) {
        CreditPurchaseStatus status = purchase.getStatus();
        return purchase.getCreditedAt() == null && (status == CreditPurchaseStatus.REJECTED
            || status == CreditPurchaseStatus.CANCELLED
            || status == CreditPurchaseStatus.EXPIRED);
    }

    /**
     * Marca una compra {@code PENDING} sin pago reportado en 24 h como
     * {@code EXPIRED} (11.6). Misma acción que un rechazo/cancelación
     * explícito de Mercado Pago para el pedido asociado — ver {@link
     * #closeAssociatedOrderIfDirect} — porque desde el punto de vista del
     * pedido el resultado es idéntico: el pago nunca llegó.
     */
    @Transactional
    public void expirePendingPurchase(UUID purchaseId) {
        purchaseRepo.findByIdForUpdate(purchaseId).ifPresent(p -> {
            if (p.getStatus() == CreditPurchaseStatus.PENDING) {
                p.setStatus(CreditPurchaseStatus.EXPIRED);
                closeAssociatedOrderIfDirect(p);
                log.info("Compra {} expirada — 24 h sin pago reportado por Mercado Pago", purchaseId);
            }
        });
    }

    // ─── mapeo de estados (diseño §Flujo de datos, tabla "Estado del pago → Acción") ──

    private void applyStatusMapping(CreditPurchase purchase, PaymentSnapshot snapshot) {
        switch (snapshot.status()) {
            case APPROVED -> {
                if (purchase.getStatus() == CreditPurchaseStatus.PENDING
                    || (purchase.getStatus() == CreditPurchaseStatus.IN_MEDIATION
                        && purchase.getCreditedAt() == null)) {
                    // Nunca se había acreditado (ni antes de la disputa ni
                    // durante) — Mercado Pago resuelve la mediación a favor
                    // del comprador: se acredita ahora (gap fix, unidad 11,
                    // feature b2c-ordering-redesign tarea B4).
                    creditApprovedPurchase(purchase);
                } else if (purchase.getStatus() == CreditPurchaseStatus.IN_MEDIATION) {
                    // Ya se había acreditado antes de entrar en mediación —
                    // vuelve a APPROVED sin acreditar una segunda vez.
                    purchase.setStatus(CreditPurchaseStatus.APPROVED);
                } else if (isClosedWithoutCredit(purchase)) {
                    // Unidad B15: el pago se aprobó DESPUÉS de que la compra se
                    // cerró (reintento con otra tarjeta tras un rechazo, pago
                    // pasadas las 24 h, o compra cancelada por la compensación
                    // del checkout con la preferencia todavía viva). Antes se
                    // ignoraba y el cliente quedaba cobrado sin almuerzos.
                    creditLateApprovedPurchase(purchase, snapshot);
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

    /**
     * Acredita un pago aprobado que llegó con la compra ya cerrada (unidad B15).
     * Idempotencia: el llamador ({@link #applyStatusMapping}) sólo entra con
     * {@code creditedAt == null}, bajo el lock de la compra; este método sella
     * {@code creditedAt} y pasa la compra a {@code APPROVED} en la misma
     * transacción, así que un webhook duplicado, la reconciliación o un segundo
     * pago aprobado ({@link #adoptDifferentPaymentId}) ya ven la compra
     * acreditada y no acreditan de nuevo. Un reembolso posterior se revierte
     * por {@link #maybeReverse} igual que en una acreditación normal.
     *
     * <p>PACK acredita a disponibles ({@link #creditPurchase}). DIRECT reutiliza
     * {@link #creditApprovedPurchase}, que con el pedido {@code CANCELADO} (lo
     * habitual tras un rechazo, un vencimiento o la compensación) acredita a
     * disponibles sin reabrirlo.
     */
    private void creditLateApprovedPurchase(CreditPurchase purchase, PaymentSnapshot snapshot) {
        log.warn("Compra {}: pago aprobado sobre compra cerrada — se acreditan {} almuerzos en disponibles "
            + "(estado previo {}, pago {})", purchase.getId(), purchase.getCreditAmount(),
            purchase.getStatus(), snapshot.paymentId());
        creditApprovedPurchase(purchase);
    }

    /**
     * Acredita una compra recién aprobada (unidad B7, extiende el gap fix B4
     * de arriba con un segundo caso). Una compra DIRECT puede aprobarse con
     * su pedido en exactamente dos estados posibles — {@link
     * OrderPlacementService#placeAwaitingPayment} nunca deja otro:
     * <ul>
     *   <li>{@code PENDIENTE_PAGO} (caso normal): se acredita COMMITTED como
     *       siempre ({@link #creditPurchase}) y el pedido pasa a {@code
     *       PENDIENTE} — un pedido programado común, con sus créditos ya
     *       comprometidos por esta MISMA compra (nunca por {@code
     *       OrderPlacementService.place()}, que la compra DIRECT ya no
     *       invoca — cierra el doble cobro verificado antes de esta unidad,
     *       ver el hallazgo de {@code b2c-ordering-redesign.md} sobre B7).</li>
     *   <li>{@code CANCELADO} (el cliente lo canceló, o el corte automático
     *       lo canceló mientras el pago seguía en curso): el pago de todos
     *       modos se aprobó, así que los almuerzos NO se pierden — se
     *       acreditan a AVAILABLE ({@link #creditAsRefundToAvailable}) en vez
     *       de COMMITTED, que exigiría reabrir un pedido ya cerrado. El
     *       pedido cancelado se deja exactamente como está, nunca se
     *       reabre.</li>
     * </ul>
     *
     * <p><b>Lock del pedido</b> (unidad B7.1, hallazgo de revisión): leer
     * {@code purchase.getOrder().getEstado()} sin bloquear la fila corría en
     * paralelo con {@link OrderPlacementService#cancel} y el corte de {@code
     * OrderConsumptionScheduler} — ambos pueden cancelar este MISMO pedido
     * mientras esta aprobación decide, y esta rama podía leer una foto vieja
     * (pedido CANCELADO con créditos igual comprometidos, o pedido PENDIENTE
     * con el stock ya restaurado por la cancelación). Por eso acá se bloquea
     * el pedido con {@link OrderRepository#findByIdForUpdate} ANTES de leer
     * su estado. Orden de locks: compra (YA bloqueada arriba por {@link
     * #applySnapshot} vía {@code findByIdForUpdate}) → pedido → billetera —
     * ni {@code cancel} ni {@code closeForPaymentFailure} bloquean nunca la
     * compra, así que no hay ciclo nuevo de deadlock con este lock extra.
     */
    private void creditApprovedPurchase(CreditPurchase purchase) {
        if (purchase.getType() != PurchaseType.DIRECT || purchase.getOrder() == null) {
            creditPurchase(purchase);
            return;
        }

        Order order = orderRepo.findByIdForUpdate(purchase.getOrder().getId())
            .orElseThrow(() -> BusinessException.notFound("order-not-found", "Pedido no encontrado"));

        if (order.getEstado() != OrderEstado.PENDIENTE_PAGO) {
            // CANCELADO (el caso conocido) o cualquier otro estado que ya no
            // espera este pago: nunca se comprometen créditos sobre un pedido
            // que no lo está esperando (unidad B15: compra cerrada y pagada tarde).
            creditAsRefundToAvailable(purchase);
            return;
        }

        creditPurchase(purchase);
        order.setEstado(OrderEstado.PENDIENTE);
    }

    /**
     * Reembolso en almuerzos (unidad B7) — mismo shape que {@link
     * #creditPurchase} (marca {@code APPROVED}, sella {@code creditedAt},
     * publica el mismo evento — su mail genérico "te acreditamos N
     * almuerzos" es válido para los dos casos) pero con {@link
     * MovementType#DIRECT_PURCHASE_REFUND} en vez de {@link
     * MovementType#DIRECT_PURCHASE}: {@code +N AVAILABLE}, nunca {@code
     * COMMITTED}, para no reabrir un pedido que ya está {@code CANCELADO}.
     */
    private void creditAsRefundToAvailable(CreditPurchase purchase) {
        MovementRef ref = MovementRef.forPurchase(purchase.getId(),
            "Reembolso en almuerzos — pedido #" + purchase.getOrder().getId() + " cancelado");
        creditLedgerService.apply(purchase.getUser().getId(), MovementType.DIRECT_PURCHASE_REFUND,
            purchase.getCreditAmount(), 0, ref);

        purchase.setStatus(CreditPurchaseStatus.APPROVED);
        purchase.setCreditedAt(clock.instant());

        eventPublisher.publishEvent(new CreditPurchaseCreditedEvent(
            purchase.getId(), purchase.getUser().getId(), purchase.getUser().getEmail(),
            purchase.getType(), purchase.getCreditAmount()));
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
     * Diseño §Flujo de datos, tabla "Estado del pago → Acción": {@code
     * rejected}/{@code cancelled} cierran la compra sin acreditar — y, si
     * era compra directa, cierran también el pedido asociado (ver {@link
     * #closeAssociatedOrderIfDirect}).
     */
    private void closeWithoutCrediting(CreditPurchase purchase, CreditPurchaseStatus status) {
        if (purchase.getStatus() != CreditPurchaseStatus.PENDING) {
            return; // ya se había resuelto (p. ej. ya acreditada) — no se cierra retroactivamente
        }
        purchase.setStatus(status);
        closeAssociatedOrderIfDirect(purchase);
    }

    /**
     * Gap fix (diseño §Flujo de datos, tabla "Estado del pago → Acción": "si
     * era compra directa, se cancela el pedido asociado"): cuando una compra
     * DIRECTA termina en un estado no pagado, el pedido que esa compra
     * intentaba pagar se cierra por el mismo camino que la cancelación del
     * cliente — {@link OrderPlacementService#closeForPaymentFailure} —
     * liberando créditos COMMITTED y restaurando stock, SIN la ventana de
     * cancelación del cliente (no aplica: el pago ya falló, el pedido de
     * todos modos no se va a cocinar).
     *
     * <p>Solo aplica a {@code DIRECT}: una {@code PACK_PURCHASE} nunca tiene
     * {@code order} asociada ({@code chk_credit_purchase_target} de V19), así
     * que rechazar/cancelar/expirar un paquete nunca toca ningún pedido.
     *
     * <p>Idempotente por partida doble: el llamador ({@link
     * #closeWithoutCrediting}/{@link #expirePendingPurchase}) ya filtra por
     * {@code status == PENDING} antes de llegar acá, y {@link
     * OrderPlacementService#closeForPaymentFailure} vuelve a filtrar por
     * {@code estado == PENDIENTE} del lado del pedido — un webhook duplicado
     * o una segunda pasada de reconciliación no liberan stock/créditos dos
     * veces aunque lleguen por caminos distintos.
     */
    private void closeAssociatedOrderIfDirect(CreditPurchase purchase) {
        if (purchase.getType() == PurchaseType.DIRECT && purchase.getOrder() != null) {
            orderPlacementService.closeForPaymentFailure(purchase.getOrder().getId());
        }
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

    private CreditPack findEnabledDayPackOrThrow() {
        return packRepo.findByPackTypeAndDeletedAtIsNullAndEnabledTrue(CreditPackType.INDIVIDUAL)
            .orElseThrow(() -> new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                "direct-purchase-unavailable",
                "La compra directa no está disponible — falta configurar el paquete INDIVIDUAL"));
    }

    /** Redondeo hacia arriba: nunca cobrar de menos por truncamiento. */
    private long unitPriceCentsFor(CreditPack dayPack) {
        return -Math.floorDiv(-dayPack.getPriceCents(), dayPack.getCreditAmount());
    }

    private String checkoutTitle(PurchaseType type, int creditAmount) {
        return type == PurchaseType.PACK
            ? "Paquete de almuerzos — Arias"
            : creditAmount + " almuerzo(s) — Arias";
    }
}
