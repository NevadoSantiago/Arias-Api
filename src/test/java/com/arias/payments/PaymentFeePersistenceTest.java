package com.arias.payments;

import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.credits.packs.CreditPackType;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PaymentFeePersistenceTest {

    @Autowired private CreditPurchaseService purchaseService;
    @Autowired private CreditPurchaseRepository purchaseRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private CreditPackRepository packRepo;
    @MockitoBean private PaymentGateway paymentGateway;

    @Test
    void applyingASnapshotStoresTheFeeAndTheNetReceived() {
        CreditPurchase purchase = pendingPurchase();

        purchaseService.applySnapshot(snapshot(purchase, 6_001L, 94_000L));

        CreditPurchase stored = purchaseRepo.findById(purchase.getId()).orElseThrow();
        assertThat(stored.getMpFeeCents()).isEqualTo(6_001L);
        assertThat(stored.getMpNetReceivedCents()).isEqualTo(94_000L);
    }

    @Test
    void aSnapshotWithoutFeeKeepsTheValuesAlreadyStored() {
        CreditPurchase purchase = pendingPurchase();
        purchaseService.applySnapshot(snapshot(purchase, 6_001L, 94_000L));

        purchaseService.applySnapshot(snapshot(purchase, null, null));

        CreditPurchase stored = purchaseRepo.findById(purchase.getId()).orElseThrow();
        assertThat(stored.getMpFeeCents()).isEqualTo(6_001L);
        assertThat(stored.getMpNetReceivedCents()).isEqualTo(94_000L);
    }

    private CreditPurchase pendingPurchase() {
        User user = userRepo.save(User.builder()
            .email("fee-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE).active(true).emailVerifiedAt(Instant.now()).build());
        CreditPack pack = packRepo.save(CreditPack.builder()
            .code("F" + System.nanoTime()).packType(CreditPackType.OTRO).nombre("Otro").creditAmount(2)
            .priceCents(1_000L).discountPercent(0).ordenDisplay(0).enabled(true).build());
        return purchaseRepo.save(CreditPurchase.builder()
            .user(user).type(PurchaseType.PACK).pack(pack).creditAmount(2).amountCents(100_000L)
            .status(CreditPurchaseStatus.PENDING).build());
    }

    private static PaymentSnapshot snapshot(CreditPurchase purchase, Long fee, Long net) {
        return new PaymentSnapshot("mp-fee-1", PaymentStatus.REJECTED, "cc_rejected_other_reason",
            purchase.getAmountCents(), "ARS", purchase.getId().toString(), 0L, fee, net);
    }
}
