package com.arias.credits;

import com.arias.common.exception.BusinessException;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Máquina de estados de {@link CreditLedgerService} — spec {@code
 * credit-ledger} completa (unidad 3): COMMIT/RELEASE/CONSUME/EXPIRATION,
 * invariante {@code saldo == SUM(deltas)}, no-sobregiro, reglas de
 * vencimiento/renovación por tipo, y compromisos paralelos sobre la misma
 * billetera (sin sobregiro).
 *
 * <p>Levanta el contexto de Spring completo contra la base real de Docker
 * (mismo patrón que {@code CreditWalletRepositoryTest}) con un {@link Clock}
 * fijo importado vía {@link FixedClockConfig} — así las pruebas de
 * vencimiento no dependen de la hora real. Todos los tests corren dentro de
 * la transacción de rollback de {@code @Transactional} EXCEPTO el de
 * concurrencia, que la suspende explícitamente porque necesita conexiones
 * reales en paralelo para ejercer el {@code SELECT ... FOR UPDATE}.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(CreditLedgerServiceTest.FixedClockConfig.class)
class CreditLedgerServiceTest {

    static final Instant FIXED_NOW = Instant.parse("2026-01-15T12:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZoneId.of("America/Argentina/Buenos_Aires"));
        }
    }

    @Autowired
    private CreditLedgerService ledgerService;

    @Autowired
    private CreditWalletRepository walletRepo;

    @Autowired
    private CreditMovementRepository movementRepo;

    @Autowired
    private UserRepository userRepo;

    private User persistTestUser(String prefix) {
        User user = User.builder()
            .email(prefix + "-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .build();
        return userRepo.save(user);
    }

    private CreditWallet seedWallet(Long userId, int available, int committed, Instant expiresAt) {
        return walletRepo.saveAndFlush(CreditWallet.builder()
            .userId(userId)
            .available(available)
            .committed(committed)
            .expiresAt(expiresAt)
            .build());
    }

    // ─── COMMIT / RELEASE / CONSUME ────────────────────────────────────────

    @Test
    @DisplayName("commit mueve saldo de AVAILABLE a COMMITTED")
    void commitMueveSaldoDeAvailableACommitted() {
        User user = persistTestUser("commit-ok");
        seedWallet(user.getId(), 5, 0, null);

        CreditMovement movement = ledgerService.commit(user.getId(), 2, MovementRef.none("pedido de prueba"));

        assertThat(movement.getType()).isEqualTo(MovementType.COMMIT);
        assertThat(movement.getDeltaAvailable()).isEqualTo(-2);
        assertThat(movement.getDeltaCommitted()).isEqualTo(2);

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(3);
        assertThat(wallet.getCommitted()).isEqualTo(2);
    }

    @Test
    @DisplayName("release devuelve saldo de COMMITTED a AVAILABLE")
    void releaseDevuelveSaldoDeCommittedAAvailable() {
        User user = persistTestUser("release-ok");
        seedWallet(user.getId(), 3, 2, null);

        ledgerService.release(user.getId(), 2, MovementRef.none("cancelación a tiempo"));

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(5);
        assertThat(wallet.getCommitted()).isEqualTo(0);
    }

    @Test
    @DisplayName("consume reduce COMMITTED sin tocar AVAILABLE")
    void consumeReduceCommittedSinTocarAvailable() {
        User user = persistTestUser("consume-ok");
        seedWallet(user.getId(), 3, 2, null);

        CreditMovement movement = ledgerService.consume(user.getId(), 2, MovementRef.none("consumo automático"));

        assertThat(movement.getType()).isEqualTo(MovementType.CONSUME);
        assertThat(movement.getDeltaAvailable()).isEqualTo(0);
        assertThat(movement.getDeltaCommitted()).isEqualTo(-2);

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(3);
        assertThat(wallet.getCommitted()).isEqualTo(0);
    }

    @Test
    @DisplayName("commit/release/consume rechazan un monto no positivo")
    void rechazaMontoNoPositivo() {
        User user = persistTestUser("non-positive");
        seedWallet(user.getId(), 5, 0, null);

        assertThatThrownBy(() -> ledgerService.commit(user.getId(), 0, MovementRef.none("x")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledgerService.commit(user.getId(), -1, MovementRef.none("x")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── No-sobregiro ───────────────────────────────────────────────────────

    @Test
    @DisplayName("saldo insuficiente falla atómicamente, sin comprometer parcialmente")
    void saldoInsuficienteFallaSinComprometerParcialmente() {
        User user = persistTestUser("insufficient");
        seedWallet(user.getId(), 1, 0, null);

        assertThatThrownBy(() -> ledgerService.commit(user.getId(), 5, MovementRef.none("pedido caro")))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "insufficient-credits");

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(1);
        assertThat(wallet.getCommitted()).isEqualTo(0);
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    // ─── Vencimiento ────────────────────────────────────────────────────────

    @Test
    @DisplayName("EXPIRATION pone en cero solo AVAILABLE — COMMITTED queda intacto")
    void expirationPoneEnCeroSoloAvailable() {
        User user = persistTestUser("expire-ok");
        seedWallet(user.getId(), 4, 3, FIXED_NOW.minus(1, ChronoUnit.DAYS));

        ledgerService.expireIfDue(user.getId());

        List<CreditMovement> movements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getType()).isEqualTo(MovementType.EXPIRATION);
        assertThat(movements.get(0).getDeltaAvailable()).isEqualTo(-4);
        assertThat(movements.get(0).getDeltaCommitted()).isEqualTo(0);

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(0);
        assertThat(wallet.getCommitted()).isEqualTo(3);
        assertThat(wallet.getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("expireIfDue es no-op si todavía no venció")
    void expireIfDueEsNoOpSiTodaviaNoVencio() {
        User user = persistTestUser("not-yet-expired");
        seedWallet(user.getId(), 4, 0, FIXED_NOW.plus(10, ChronoUnit.DAYS));

        ledgerService.expireIfDue(user.getId());

        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(4);
    }

    /**
     * Suspende la transacción de test (igual que el test de concurrencia):
     * dentro del rollback ambiental de {@code @Transactional} de la clase,
     * {@code commit()} se ejecutaría DENTRO de la misma transacción física
     * que el test (join, no una nueva), así que una excepción solo marca
     * "rollback-only" sin revertir de inmediato — las lecturas posteriores,
     * en esa misma transacción, verían el estado a medio aplicar. Para
     * observar la reversión atómica real hace falta que {@code commit()}
     * abra y cierre su PROPIA transacción física, como en producción.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("guard perezoso: apply() expira antes de intentar comprometer, y todo falla atómico si ya no alcanza")
    void guardPerezosoExpiraAntesDeComprometerYRevierteSiNoAlcanza() {
        User user = persistTestUser("lazy-guard");
        Instant expiredAt = FIXED_NOW.minus(1, ChronoUnit.DAYS);
        seedWallet(user.getId(), 5, 0, expiredAt);

        try {
            // El saldo ya venció; comprometer 3 debería fallar (0 disponible tras expirar).
            assertThatThrownBy(() -> ledgerService.commit(user.getId(), 3, MovementRef.none("intento tardío")))
                .isInstanceOf(BusinessException.class);

            // Como toda la transacción de apply() se revierte, ni el vencimiento
            // ni el intento de commit dejan rastro — el saldo queda EXACTAMENTE
            // como estaba antes de la llamada (atomicidad de "todo o nada").
            CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
            assertThat(wallet.getAvailable()).isEqualTo(5);
            assertThat(wallet.getExpiresAt()).isEqualTo(expiredAt);
            assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();

            // Un segundo intento por un monto que SÍ cabe en el saldo ya vencido debe fallar también.
            assertThatThrownBy(() -> ledgerService.commit(user.getId(), 1, MovementRef.none("otro intento")))
                .isInstanceOf(BusinessException.class);
        } finally {
            movementRepo.deleteAll(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId()));
            walletRepo.findById(user.getId()).ifPresent(walletRepo::delete);
            userRepo.delete(user);
        }
    }

    // ─── Renovación de vencimiento por tipo ────────────────────────────────

    @Test
    @DisplayName("WELCOME_GRANT establece el vencimiento inicial (90 días) sobre una billetera sin fecha")
    void welcomeGrantEstableceVencimientoInicial() {
        User user = persistTestUser("welcome-grant");

        ledgerService.apply(user.getId(), MovementType.WELCOME_GRANT, 5, 0, MovementRef.none("almuerzo de bienvenida"));

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(5);
        assertThat(wallet.getExpiresAt()).isEqualTo(FIXED_NOW.plus(90, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("PACK_PURCHASE renueva el vencimiento aunque ya hubiera uno vigente")
    void packPurchaseRenuevaVencimiento() {
        User user = persistTestUser("pack-purchase");
        seedWallet(user.getId(), 2, 0, FIXED_NOW.plus(5, ChronoUnit.DAYS));

        ledgerService.apply(user.getId(), MovementType.PACK_PURCHASE, 20, 0, MovementRef.none("compra de paquete"));

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(22);
        assertThat(wallet.getExpiresAt()).isEqualTo(FIXED_NOW.plus(90, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("DIRECT_PURCHASE no renueva el vencimiento")
    void directPurchaseNoRenuevaVencimiento() {
        User user = persistTestUser("direct-purchase");
        Instant original = FIXED_NOW.plus(5, ChronoUnit.DAYS);
        seedWallet(user.getId(), 2, 0, original);

        ledgerService.apply(user.getId(), MovementType.DIRECT_PURCHASE, 0, 5, MovementRef.none("compra directa"));

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getCommitted()).isEqualTo(5);
        assertThat(wallet.getAvailable()).isEqualTo(2);
        assertThat(wallet.getExpiresAt()).isEqualTo(original);
    }

    @Test
    @DisplayName("PAYMENT_REVERSAL no renueva el vencimiento")
    void paymentReversalNoRenuevaVencimiento() {
        User user = persistTestUser("payment-reversal");
        Instant original = FIXED_NOW.plus(5, ChronoUnit.DAYS);
        seedWallet(user.getId(), 10, 0, original);

        ledgerService.apply(user.getId(), MovementType.PAYMENT_REVERSAL, -3, 0, MovementRef.none("reembolso"));

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(7);
        assertThat(wallet.getExpiresAt()).isEqualTo(original);
    }

    // ─── Invariante saldo == SUM(deltas) ───────────────────────────────────

    @Test
    @DisplayName("invariante: wallet.available/committed == SUM(delta_available)/SUM(delta_committed)")
    void saldoDeLaBilleteraCoincideConSumaDeMovimientos() {
        User user = persistTestUser("invariant");

        ledgerService.apply(user.getId(), MovementType.WELCOME_GRANT, 5, 0, MovementRef.none("bienvenida"));
        ledgerService.commit(user.getId(), 2, MovementRef.none("pedido 1"));
        ledgerService.release(user.getId(), 2, MovementRef.none("cancelación"));
        ledgerService.commit(user.getId(), 3, MovementRef.none("pedido 2"));
        ledgerService.consume(user.getId(), 3, MovementRef.none("consumo"));

        List<CreditMovement> movements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        int sumAvailable = movements.stream().mapToInt(CreditMovement::getDeltaAvailable).sum();
        int sumCommitted = movements.stream().mapToInt(CreditMovement::getDeltaCommitted).sum();

        CreditWallet wallet = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(sumAvailable);
        assertThat(wallet.getCommitted()).isEqualTo(sumCommitted);
        assertThat(wallet.getAvailable()).isEqualTo(2);
        assertThat(wallet.getCommitted()).isEqualTo(0);
    }

    // ─── Concurrencia ───────────────────────────────────────────────────────

    /**
     * Dos {@code commit()} en paralelo sobre la MISMA billetera, cada uno
     * pidiendo más de la mitad del saldo disponible: el {@code SELECT ...
     * FOR UPDATE} de {@code findByIdForUpdate} debe serializarlos, de modo
     * que exactamente uno tenga éxito y el otro falle por saldo insuficiente
     * — nunca ambos, nunca sobregiro.
     *
     * <p>Suspende la transacción de test (@{@code Transactional(NOT_SUPPORTED)})
     * porque necesita que el setup quede REALMENTE committeado para que los
     * dos threads (con sus propias conexiones) puedan verlo y contender por
     * el lock de fila. Limpia manualmente al final porque no hay rollback.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("compromisos paralelos sobre la misma billetera no sobregiran")
    void compromisosParalelosSobreLaMismaBilleteraNoSobregiran() throws InterruptedException {
        User user = persistTestUser("concurrency");
        seedWallet(user.getId(), 5, 0, null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> attempt = () -> {
                try {
                    ledgerService.commit(user.getId(), 3, MovementRef.none("compromiso paralelo"));
                    return true;
                } catch (BusinessException ex) {
                    return false;
                }
            };

            Future<Boolean> futureA = pool.submit(attempt);
            Future<Boolean> futureB = pool.submit(attempt);

            boolean resultA = get(futureA);
            boolean resultB = get(futureB);

            assertThat(resultA ^ resultB)
                .as("exactamente uno de los dos compromisos paralelos debe tener éxito")
                .isTrue();

            // findById (no findByIdForUpdate): ya no necesitamos el lock acá, y
            // el @Lock + @Query custom exige una transacción explícita que este
            // test, deliberadamente sin transacción ambiente, no tiene.
            CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
            assertThat(wallet.getAvailable()).isEqualTo(2);
            assertThat(wallet.getCommitted()).isEqualTo(3);
            assertThat(wallet.getAvailable()).isGreaterThanOrEqualTo(0);
            assertThat(wallet.getCommitted()).isGreaterThanOrEqualTo(0);
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            // Sin rollback automático (transacción suspendida) — limpieza manual.
            movementRepo.deleteAll(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId()));
            walletRepo.findById(user.getId()).ifPresent(walletRepo::delete);
            userRepo.delete(user);
        }
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BusinessException) {
                return false;
            }
            throw new RuntimeException(e.getCause());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
