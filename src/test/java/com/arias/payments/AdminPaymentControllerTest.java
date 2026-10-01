package com.arias.payments;

import com.arias.common.exception.BusinessException;
import com.arias.common.security.JwtUser;
import com.arias.credits.packs.CreditPack;
import com.arias.credits.packs.CreditPackRepository;
import com.arias.credits.packs.CreditPackType;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Admin payments report. The restaurant timezone is America/Argentina/Buenos_Aires
 * (UTC-3), so 2026-03-10 spans 2026-03-10T03:00Z to 2026-03-11T03:00Z.
 */
@SpringBootTest
@Transactional
class AdminPaymentControllerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    @Autowired private AdminPaymentController controller;
    @Autowired private CreditPurchaseRepository purchaseRepo;
    @Autowired private CreditPackRepository packRepo;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EntityManager em;
    @MockitoBean private PaymentGateway paymentGateway;

    @BeforeEach
    void authenticateAsSuperAdmin() {
        JwtUser principal = new JwtUser(1L, "admin@arias.com", Role.SUPER_ADMIN, null, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void defaultFilterListsOnlyApprovedPaymentsAndSummarisesThem() {
        User user = user("ana");
        CreditPack sugerido = pack(CreditPackType.SUGERIDO);
        CreditPack individual = pack(CreditPackType.INDIVIDUAL);
        CreditPurchase a = purchase(user, sugerido, null, CreditPurchaseStatus.APPROVED, 100_000L,
            Instant.parse("2026-03-10T15:00:00Z"), 6_000L, 94_000L);
        purchase(user, individual, null, CreditPurchaseStatus.APPROVED, 20_000L,
            Instant.parse("2026-03-10T16:00:00Z"), null, null);
        Order order = order(user);
        purchase(user, null, order, CreditPurchaseStatus.APPROVED, 30_000L,
            Instant.parse("2026-03-10T17:00:00Z"), 1_500L, 28_500L);
        purchase(user, sugerido, null, CreditPurchaseStatus.REJECTED, 99_000L,
            Instant.parse("2026-03-10T18:00:00Z"), null, null);
        purchase(user, sugerido, null, CreditPurchaseStatus.IN_MEDIATION, 10_000L,
            Instant.parse("2026-03-10T19:00:00Z"), 100L, 9_900L);
        purchase(user, sugerido, null, CreditPurchaseStatus.REVERSED, 10_000L,
            Instant.parse("2026-03-10T20:00:00Z"), 100L, 9_900L);

        AdminPaymentReportDto report = controller.list(DAY, DAY, AdminPaymentFilter.APPROVED);

        assertThat(report.rows()).hasSize(3);
        assertThat(report.rows()).extracting(AdminPaymentRowDto::status)
            .containsOnly(CreditPurchaseStatus.APPROVED);
        AdminPaymentRowDto first = report.rows().stream()
            .filter(r -> r.purchaseId().equals(a.getId())).findFirst().orElseThrow();
        assertThat(first.customer()).isEqualTo("ana");
        assertThat(first.kind()).isEqualTo("SUGERIDO");
        assertThat(first.packName()).isEqualTo(sugerido.getNombre());
        assertThat(first.feeCents()).isEqualTo(6_000L);
        assertThat(first.netCents()).isEqualTo(94_000L);
        assertThat(first.occurredAt()).isEqualTo(Instant.parse("2026-03-10T15:00:00Z"));
        assertThat(report.rows()).filteredOn(r -> r.kind().equals("DIRECT"))
            .singleElement().satisfies(r -> assertThat(r.orderId()).isEqualTo(order.getId()));

        AdminPaymentSummaryDto s = report.summary();
        assertThat(s.approvedCount()).isEqualTo(3);
        assertThat(s.grossCents()).isEqualTo(150_000L);
        assertThat(s.feeCents()).isEqualTo(7_500L);
        assertThat(s.netCents()).isEqualTo(122_500L);
        assertThat(s.rowsWithoutFee()).isEqualTo(1);
        assertThat(s.inMediationCount()).isEqualTo(1);
        assertThat(s.reversedCount()).isEqualTo(1);
        assertThat(s.byKind()).extracting(AdminPaymentSummaryDto.KindBreakdown::kind)
            .containsExactly("INDIVIDUAL", "SUGERIDO", "OTRO", "DIRECT");
        assertThat(s.byKind()).extracting(AdminPaymentSummaryDto.KindBreakdown::count)
            .containsExactly(1L, 1L, 0L, 1L);
        assertThat(s.byKind()).extracting(AdminPaymentSummaryDto.KindBreakdown::grossCents)
            .containsExactly(20_000L, 100_000L, 0L, 30_000L);
    }

    @Test
    void allFilterListsEveryStatusButIncomeStaysApprovedOnly() {
        User user = user("beto");
        CreditPack pack = pack(CreditPackType.OTRO);
        purchase(user, pack, null, CreditPurchaseStatus.APPROVED, 10_000L,
            Instant.parse("2026-03-10T15:00:00Z"), 100L, 9_900L);
        purchase(user, pack, null, CreditPurchaseStatus.REJECTED, 10_000L,
            Instant.parse("2026-03-10T15:00:00Z"), null, null);

        AdminPaymentReportDto report = controller.list(DAY, DAY, AdminPaymentFilter.ALL);

        assertThat(report.rows()).hasSize(2);
        assertThat(report.summary().approvedCount()).isEqualTo(1);
        assertThat(report.summary().grossCents()).isEqualTo(10_000L);
    }

    @Test
    void rangeIsInclusiveInTheRestaurantTimezone() {
        User user = user("cami");
        CreditPack pack = pack(CreditPackType.OTRO);
        // 02:59Z on the 10th is still the 9th in Buenos Aires; 03:00Z opens the 10th.
        purchase(user, pack, null, CreditPurchaseStatus.APPROVED, 1_000L, Instant.parse("2026-03-10T02:59:59Z"), null, null);
        CreditPurchase opens = purchase(user, pack, null, CreditPurchaseStatus.APPROVED, 2_000L, Instant.parse("2026-03-10T03:00:00Z"), null, null);
        CreditPurchase closes = purchase(user, pack, null, CreditPurchaseStatus.APPROVED, 3_000L, Instant.parse("2026-03-11T02:59:59Z"), null, null);
        purchase(user, pack, null, CreditPurchaseStatus.APPROVED, 4_000L, Instant.parse("2026-03-11T03:00:00Z"), null, null);

        AdminPaymentReportDto report = controller.list(DAY, DAY, AdminPaymentFilter.APPROVED);

        assertThat(report.rows()).extracting(AdminPaymentRowDto::purchaseId)
            .containsExactlyInAnyOrder(opens.getId(), closes.getId());
    }

    @Test
    void aPaymentNeverApprovedFallsBackToItsCreationDate() {
        User user = user("dani");
        CreditPurchase p = purchase(user, pack(CreditPackType.OTRO), null, CreditPurchaseStatus.REJECTED,
            1_000L, null, null, null);
        em.flush();
        em.createNativeQuery("UPDATE credit_purchase SET created_at = :t WHERE id = :id")
            .setParameter("t", Instant.parse("2026-03-10T15:00:00Z")).setParameter("id", p.getId())
            .executeUpdate();
        em.clear();

        AdminPaymentReportDto report = controller.list(DAY, DAY, AdminPaymentFilter.ALL);

        assertThat(report.rows()).extracting(AdminPaymentRowDto::purchaseId).contains(p.getId());
    }

    @Test
    void fromAfterToIsABadRequest() {
        assertThatThrownBy(() -> controller.list(DAY.plusDays(1), DAY, AdminPaymentFilter.APPROVED))
            .isInstanceOf(BusinessException.class);
    }

    // ─── fixtures ────────────────────────────────────────────────────────

    private User user(String nickname) {
        return userRepo.save(User.builder()
            .email(nickname + "-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE).active(true).emailVerifiedAt(Instant.now()).nickname(nickname).build());
    }

    /** INDIVIDUAL/SUGERIDO are unique among live packs, so reuse the one that already exists. */
    private CreditPack pack(CreditPackType type) {
        return packRepo.findAll().stream()
            .filter(p -> p.getPackType() == type && p.getDeletedAt() == null && type != CreditPackType.OTRO)
            .findFirst()
            .orElseGet(() -> packRepo.save(CreditPack.builder()
                .code("T" + UUID.randomUUID().toString().substring(0, 8))
                .packType(type).nombre("Pack " + type).creditAmount(2).priceCents(1_000L)
                .discountPercent(0).ordenDisplay(0).enabled(true).build()));
    }

    private Order order(User user) {
        return orderRepo.save(Order.builder().user(user).fecha(DAY)
            .pickupAt(Instant.parse("2026-03-10T16:00:00Z")).estado(OrderEstado.CONFIRMADO).creditTotal(2).build());
    }

    private CreditPurchase purchase(User user, CreditPack pack, Order order, CreditPurchaseStatus status,
                                    long amount, Instant creditedAt, Long fee, Long net) {
        return purchaseRepo.save(CreditPurchase.builder()
            .user(user).type(order != null ? PurchaseType.DIRECT : PurchaseType.PACK)
            .pack(pack).order(order).creditAmount(2).amountCents(amount).status(status)
            .mpPaymentId("mp-" + UUID.randomUUID()).creditedAt(creditedAt)
            .mpFeeCents(fee).mpNetReceivedCents(net).build());
    }
}
