package com.arias.payments;

import com.arias.common.exception.BusinessException;
import com.arias.credits.CreditMovementRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real persistence: confirming an approved payment credits the purchase exactly once. */
@SpringBootTest
@Transactional
class PurchaseConfirmationCreditTest {

    @Autowired private PurchaseConfirmationService confirmation;
    @Autowired private CreditPurchaseService purchaseService;
    @Autowired private CreditPurchaseRepository purchaseRepo;
    @Autowired private CreditPackRepository packRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private CreditWalletRepository walletRepo;
    @Autowired private CreditMovementRepository movementRepo;
    @Autowired private EntityManager entityManager;

    @MockitoBean private PaymentGateway paymentGateway;

    private User persistUser(String prefix) {
        long n = System.nanoTime();
        return userRepo.save(User.builder()
            .email(prefix + "-" + n + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .phone("+549" + (2_000_000_000L + (n % 900_000_000L)))
            .nickname("Apodo-" + n)
            .build());
    }

    private CreditPurchase persistPendingPackPurchase(User user) {
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("CNF-" + UUID.randomUUID().toString().substring(0, 8))
            .nombre("Dia")
            .creditAmount(2)
            .priceCents(3_000L)
            .discountPercent(0)
            .ordenDisplay(0)
            .enabled(true)
            .build());
        when(paymentGateway.createCheckout(any()))
            .thenReturn(new CheckoutSession("pref-confirm", "https://mp.test/init"));
        CreditPurchaseCheckoutDto dto = purchaseService.createPurchase(user.getId(),
            new CreatePurchaseRequest(PurchaseType.PACK, pack.getId(), null, 1));
        return purchaseRepo.findById(dto.purchaseId()).orElseThrow();
    }

    @Test
    void ownerConfirmCreditsAnApprovedPaymentExactlyOnce() {
        User user = persistUser("confirm-ok");
        CreditPurchase purchase = persistPendingPackPurchase(user);
        PaymentSnapshot approved = new PaymentSnapshot("mp-confirm", PaymentStatus.APPROVED, "accredited",
            purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L);
        when(paymentGateway.findRecentByExternalReference(purchase.getId().toString()))
            .thenReturn(List.of(approved));
        entityManager.flush();
        entityManager.clear();

        CreditPurchaseDto first = confirmation.confirm(user.getId(), purchase.getId());
        CreditPurchaseDto second = confirmation.confirm(user.getId(), purchase.getId());

        assertThat(first.status()).isEqualTo(CreditPurchaseStatus.APPROVED);
        assertThat(second.status()).isEqualTo(CreditPurchaseStatus.APPROVED);
        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(2);
        assertThat(movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId())).hasSize(1);
        verify(paymentGateway, times(1)).findRecentByExternalReference(purchase.getId().toString());
    }

    @Test
    void foreignUserGets404AndTheGatewayIsNeverCalled() {
        User owner = persistUser("confirm-owner");
        User other = persistUser("confirm-other");
        CreditPurchase purchase = persistPendingPackPurchase(owner);
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> confirmation.confirm(other.getId(), purchase.getId()))
            .isInstanceOf(BusinessException.class);

        verify(paymentGateway, never()).findRecentByExternalReference(any());
    }
}
