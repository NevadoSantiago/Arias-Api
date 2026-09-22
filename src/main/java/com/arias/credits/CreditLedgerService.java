package com.arias.credits;

import com.arias.common.exception.BusinessException;
import com.arias.restaurantconfig.RestaurantConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Libro mayor de créditos — spec {@code credit-ledger}. {@link #apply} es el
 * ÚNICO método que puede mutar {@link CreditWallet}: bloquea la fila con
 * {@code SELECT ... FOR UPDATE} ({@link CreditWalletRepository#findByIdForUpdate}),
 * aplica el guard perezoso de vencimiento, valida el invariante de no-sobregiro
 * y persiste el movimiento append-only + el saldo materializado en la misma
 * transacción (diseño §Decisión 3).
 *
 * <p>{@link #commit}/{@link #release}/{@link #consume} son atajos de {@link
 * #apply} para los tres movimientos del ciclo de vida de un pedido (diseño
 * §Decisión 4). Los demás tipos ({@code WELCOME_GRANT}, {@code
 * PACK_PURCHASE}, {@code DIRECT_PURCHASE}, {@code PAYMENT_REVERSAL}, {@code
 * ADMIN_ADJUSTMENT}) se disparan llamando a {@link #apply} directamente
 * desde las unidades que los originan (5 y 11) — esta clase no los conoce.
 *
 * <p><b>No-sobregiro</b>: ningún tipo de movimiento puede dejar {@code
 * available} o {@code committed} negativos — el CHECK {@code
 * chk_credit_wallet_non_negative} de V17 no distingue por tipo, así que esta
 * clase valida el mismo invariante ANTES de tocar la base, para fallar con
 * un {@link BusinessException} legible en vez de una
 * {@code DataIntegrityViolationException} del driver (el CHECK queda como
 * última línea de defensa, no la primera).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreditLedgerService {

    private final CreditWalletRepository walletRepo;
    private final CreditMovementRepository movementRepo;
    private final RestaurantConfigRepository restaurantConfigRepo;
    private final Clock clock;

    /**
     * Único punto de mutación de saldo. Lee la billetera bloqueada (o la crea
     * vacía si es el primer movimiento del usuario), aplica el vencimiento
     * vencido si corresponde, valida que ambos deltas no dejen el saldo
     * negativo, y persiste billetera + movimiento en la misma transacción.
     *
     * <p>Si la validación falla, toda la transacción se revierte — incluido
     * cualquier vencimiento aplicado por el guard perezoso en esta misma
     * llamada. Es intencional: la operación completa es atómica; un intento
     * fallido no deja efectos secundarios parciales. El saldo vencido se
     * termina de limpiar en el próximo intento exitoso o en el barrido
     * horario de {@code CreditExpiryScheduler}.
     */
    @Transactional
    public CreditMovement apply(Long userId, MovementType type,
                                 int deltaAvailable, int deltaCommitted, MovementRef ref) {
        CreditWallet wallet = walletRepo.findByIdForUpdate(userId)
            .orElseGet(() -> walletRepo.save(CreditWallet.emptyFor(userId)));

        expireIfDue(wallet);

        int newAvailable = wallet.getAvailable() + deltaAvailable;
        int newCommitted = wallet.getCommitted() + deltaCommitted;
        if (newAvailable < 0 || newCommitted < 0) {
            throw BusinessException.conflict("insufficient-credits",
                "Saldo de créditos insuficiente para esta operación");
        }

        wallet.setAvailable(newAvailable);
        wallet.setCommitted(newCommitted);

        // Vencimiento: se fija en el primer otorgamiento (billetera sin fecha
        // todavía — típicamente WELCOME_GRANT) y se RENUEVA solo por compra de
        // paquete — spec "Renovación del vencimiento solo por compra de
        // paquetes". DIRECT_PURCHASE (deltaAvailable = 0, va directo a
        // committed) y PAYMENT_REVERSAL (deltaAvailable negativo) nunca caen
        // en esta rama, así que nunca renuevan — igual que exige la spec.
        if (type == MovementType.PACK_PURCHASE
            || (wallet.getExpiresAt() == null && deltaAvailable > 0)) {
            int expiryDays = restaurantConfigRepo.getSingleton().getCreditExpiryDays();
            wallet.setExpiresAt(clock.instant().plus(expiryDays, ChronoUnit.DAYS));
        }

        walletRepo.save(wallet);

        CreditMovement movement = CreditMovement.builder()
            .userId(userId)
            .type(type)
            .deltaAvailable(deltaAvailable)
            .deltaCommitted(deltaCommitted)
            .orderId(ref != null ? ref.orderId() : null)
            .purchaseId(ref != null ? ref.purchaseId() : null)
            .description(ref != null ? ref.description() : null)
            .build();
        return movementRepo.save(movement);
    }

    /** Compromiso al confirmar un pedido: AVAILABLE → COMMITTED (diseño §Decisión 4). */
    @Transactional
    public CreditMovement commit(Long userId, int amount, MovementRef ref) {
        requirePositive(amount);
        return apply(userId, MovementType.COMMIT, -amount, amount, ref);
    }

    /** Devolución al cancelar a tiempo: COMMITTED → AVAILABLE (diseño §Decisión 7). */
    @Transactional
    public CreditMovement release(Long userId, int amount, MovementRef ref) {
        requirePositive(amount);
        return apply(userId, MovementType.RELEASE, amount, -amount, ref);
    }

    /** Consumo definitivo en el punto de confirmación (pickup − lead, diseño §Decisión 4). */
    @Transactional
    public CreditMovement consume(Long userId, int amount, MovementRef ref) {
        requirePositive(amount);
        return apply(userId, MovementType.CONSUME, 0, -amount, ref);
    }

    /**
     * Reversión por reembolso/contracargo de Mercado Pago (unidad 11, diseño
     * §Flujo de datos "Compra con Mercado Pago"): a diferencia de {@link
     * #commit}, esta operación NUNCA falla por saldo insuficiente — es una
     * reconciliación contable, no un gasto que el usuario elige. Se acota al
     * AVAILABLE realmente disponible ("hasta 0 si ya se gastó"); el resto de
     * {@code amount} que no alcanza a revertirse queda documentado por el
     * llamador ({@code MercadoPagoWebhookController}), que lleva la cuenta de
     * cuánto de esta compra ya se intentó revertir en {@code
     * credit_purchase.credits_reversed} — el CHECK de no-negativo de la
     * billetera queda como última línea de defensa, nunca se llega a violar
     * porque el delta ya viene acotado a {@code wallet.available}.
     *
     * @return el movimiento creado, o {@code null} si no había nada
     *         disponible para revertir (todo ya estaba consumido).
     */
    @Transactional
    public CreditMovement reverse(Long userId, int amount, MovementRef ref) {
        requirePositive(amount);
        CreditWallet wallet = walletRepo.findByIdForUpdate(userId)
            .orElseGet(() -> walletRepo.save(CreditWallet.emptyFor(userId)));
        int actual = Math.min(amount, wallet.getAvailable());
        if (actual <= 0) {
            return null;
        }
        return apply(userId, MovementType.PAYMENT_REVERSAL, -actual, 0, ref);
    }

    /**
     * Otorga el almuerzo de bienvenida — spec {@code self-registration},
     * "Otorgamiento único": exactamente 1 crédito, no transferible, una sola
     * vez por cuenta (diseño §Decisión 10). Lo disparan {@code
     * RegistrationService#verifyEmail} y {@code GoogleAuthService} en el
     * instante exacto en que cada uno deja {@code email_verified_at} seteado
     * por primera vez — ambos ya filtran con ese chequeo, así que en el uso
     * normal esta llamada nunca se repite para la misma cuenta.
     *
     * <p><b>La enforcement real es el índice único parcial</b> {@code
     * uq_credit_movement_welcome} (V17) — nunca se reemplaza por un flag de
     * aplicación. El {@code existsByUserIdAndType} de acá abajo es apenas un
     * atajo para el reintento SECUENCIAL normal (reenvío de verificación,
     * relogin con Google después de haber verificado por correo, doble clic
     * del usuario): evita intentar el INSERT — y con él, disparar el
     * rollback de la transacción entera del llamador — en el caso feliz. Se
     * une a la transacción del llamador ({@code Propagation.REQUIRED}, no
     * {@code REQUIRES_NEW}) a propósito: en el alta por Google el usuario
     * puede estar siendo creado en esta MISMA transacción todavía sin
     * commitear, y una transacción aislada no vería esa fila (violación de
     * FK). El costo de esta decisión es que una carrera verdaderamente
     * simultánea (no un reintento secuencial) puede chocar contra el índice
     * y abortar esa request puntual — preferible a otorgar dos veces o a
     * dejar la conexión de otro reintento en estado abortado.
     */
    @Transactional
    public void grantWelcomeLunch(Long userId) {
        if (movementRepo.existsByUserIdAndType(userId, MovementType.WELCOME_GRANT)) {
            return;
        }
        apply(userId, MovementType.WELCOME_GRANT, 1, 0, MovementRef.none("Almuerzo de bienvenida"));
    }

    /**
     * Guard perezoso de vencimiento para un usuario puntual: bloquea su
     * billetera y expira el saldo si corresponde. Lo llama tanto {@code
     * CreditExpiryScheduler} (barrido horario, para usuarios inactivos) como,
     * indirectamente, {@link #apply} en cada mutación (para usuarios activos).
     */
    @Transactional
    public void expireIfDue(Long userId) {
        walletRepo.findByIdForUpdate(userId).ifPresent(this::expireIfDue);
    }

    /** Saldo del usuario, o una billetera vacía si nunca tuvo movimientos — solo lectura, sin bloqueo. */
    @Transactional(readOnly = true)
    public CreditWallet getWallet(Long userId) {
        return walletRepo.findById(userId).orElseGet(() -> CreditWallet.emptyFor(userId));
    }

    /** Historial completo de movimientos del usuario, más reciente primero. */
    @Transactional(readOnly = true)
    public List<CreditMovement> getMovements(Long userId) {
        return movementRepo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Vencimiento sobre una billetera YA bloqueada por el llamador ({@link
     * #apply} o {@link #expireIfDue(Long)}). {@code committed} nunca se toca
     * — un pedido programado se sostiene aunque el saldo general haya vencido
     * (spec "Movimiento de vencimiento auditable").
     */
    private void expireIfDue(CreditWallet wallet) {
        Instant now = clock.instant();
        if (wallet.getExpiresAt() == null
            || wallet.getExpiresAt().isAfter(now)
            || wallet.getAvailable() <= 0) {
            return;
        }

        int expired = wallet.getAvailable();
        wallet.setAvailable(0);
        wallet.setExpiresAt(null);
        walletRepo.save(wallet);

        movementRepo.save(CreditMovement.builder()
            .userId(wallet.getUserId())
            .type(MovementType.EXPIRATION)
            .deltaAvailable(-expired)
            .deltaCommitted(0)
            .description("Vencimiento de saldo AVAILABLE")
            .build());

        log.info("Vencimiento aplicado — user={} available_vencido={}", wallet.getUserId(), expired);
    }

    private void requirePositive(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("El monto debe ser positivo: " + amount);
        }
    }
}
