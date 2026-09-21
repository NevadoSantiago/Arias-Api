package com.arias.credits;

import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Levanta el contexto de Spring completo (Flyway aplica V15–V17 sobre la
 * base real de test) y ejerce {@link CreditWalletRepository} /
 * {@link CreditMovementRepository} contra esas tablas — spec {@code
 * credit-ledger} (unidad 2: solo esquema/repos, sin máquina de estados
 * todavía, eso es {@code CreditLedgerService} en la unidad 3).
 *
 * <p>{@code @Transactional} hace rollback al final de cada test: la base
 * compartida de desarrollo queda intacta.
 */
@SpringBootTest
@Transactional
class CreditWalletRepositoryTest {

    @Autowired
    private CreditWalletRepository walletRepo;

    @Autowired
    private CreditMovementRepository movementRepo;

    @Autowired
    private UserRepository userRepo;

    private User persistTestUser(String email) {
        User user = User.builder()
            .email(email)
            .role(Role.EMPLOYEE)
            .active(true)
            .build();
        return userRepo.save(user);
    }

    @Test
    void findByIdForUpdateDevuelveVacioSinBilletera() {
        User user = persistTestUser("ledger-empty-" + System.nanoTime() + "@test.arias.com");

        Optional<CreditWallet> found = walletRepo.findByIdForUpdate(user.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void creaYRecuperaBilleteraConSaldoPorDefectoEnCero() {
        User user = persistTestUser("ledger-wallet-" + System.nanoTime() + "@test.arias.com");

        CreditWallet saved = walletRepo.save(CreditWallet.emptyFor(user.getId()));

        assertThat(saved.getAvailable()).isEqualTo(0);
        assertThat(saved.getCommitted()).isEqualTo(0);
        assertThat(saved.getExpiresAt()).isNull();

        Optional<CreditWallet> locked = walletRepo.findByIdForUpdate(user.getId());
        assertThat(locked).isPresent();
        assertThat(locked.get().getUserId()).isEqualTo(user.getId());
    }

    @Test
    void persisteYRecuperaMovimientosOrdenadosPorFechaDescendente() {
        User user = persistTestUser("ledger-movements-" + System.nanoTime() + "@test.arias.com");
        walletRepo.save(CreditWallet.emptyFor(user.getId()));

        movementRepo.save(CreditMovement.builder()
            .userId(user.getId())
            .type(MovementType.WELCOME_GRANT)
            .deltaAvailable(5)
            .deltaCommitted(0)
            .description("Almuerzo de bienvenida")
            .build());
        movementRepo.save(CreditMovement.builder()
            .userId(user.getId())
            .type(MovementType.COMMIT)
            .deltaAvailable(-2)
            .deltaCommitted(2)
            .description("Compromiso de pedido")
            .build());

        var movements = movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId());

        assertThat(movements).hasSize(2);
        assertThat(movements.get(0).getType()).isEqualTo(MovementType.COMMIT);
        assertThat(movements.get(1).getType()).isEqualTo(MovementType.WELCOME_GRANT);
        assertThat(movements.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    void indiceUnicoParcialRechazaUnSegundoWelcomeGrantParaElMismoUsuario() {
        User user = persistTestUser("ledger-welcome-" + System.nanoTime() + "@test.arias.com");
        walletRepo.save(CreditWallet.emptyFor(user.getId()));
        movementRepo.saveAndFlush(CreditMovement.builder()
            .userId(user.getId())
            .type(MovementType.WELCOME_GRANT)
            .deltaAvailable(5)
            .deltaCommitted(0)
            .build());

        assertThatThrownBy(() -> movementRepo.saveAndFlush(CreditMovement.builder()
                .userId(user.getId())
                .type(MovementType.WELCOME_GRANT)
                .deltaAvailable(5)
                .deltaCommitted(0)
                .build()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkNonNegativeRechazaSaldoNegativoEnLaBilletera() {
        User user = persistTestUser("ledger-negative-" + System.nanoTime() + "@test.arias.com");

        assertThatThrownBy(() -> walletRepo.saveAndFlush(CreditWallet.builder()
                .userId(user.getId())
                .available(-1)
                .committed(0)
                .build()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void expiresAtSePersisteYSeRecupera() {
        User user = persistTestUser("ledger-expiry-" + System.nanoTime() + "@test.arias.com");
        Instant expiry = Instant.now().plus(90, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        walletRepo.saveAndFlush(CreditWallet.builder()
            .userId(user.getId())
            .available(5)
            .committed(0)
            .expiresAt(expiry)
            .build());

        CreditWallet reloaded = walletRepo.findByIdForUpdate(user.getId()).orElseThrow();
        assertThat(reloaded.getExpiresAt()).isEqualTo(expiry);
    }
}
