package com.arias.payments;

import com.arias.common.exception.BusinessException;
import com.arias.credits.packs.CreditPackType;
import com.arias.restaurantconfig.RestaurantConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Admin payments report: purchases in a date range plus income net of the real Mercado Pago fee. */
@Service
@RequiredArgsConstructor
public class AdminPaymentService {

    static final String DIRECT = "DIRECT";
    /** Pie order: the three pack types, then purchases paid straight for an order. */
    private static final List<String> KINDS = List.of(
        CreditPackType.INDIVIDUAL.name(), CreditPackType.SUGERIDO.name(), CreditPackType.OTRO.name(), DIRECT);

    private final CreditPurchaseRepository purchaseRepo;
    private final RestaurantConfigRepository configRepo;

    /** {@code from} and {@code to} are inclusive days in the restaurant timezone. */
    @Transactional(readOnly = true)
    public AdminPaymentReportDto report(LocalDate from, LocalDate to, AdminPaymentFilter filter) {
        if (from == null || to == null) {
            throw BusinessException.badRequest("payments-range-required", "Indicá las fechas desde y hasta.");
        }
        if (from.isAfter(to)) {
            throw BusinessException.badRequest("payments-invalid-range",
                "La fecha desde no puede ser posterior a la fecha hasta.");
        }
        ZoneId zone = ZoneId.of(configRepo.getSingleton().getTimezone());
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

        List<CreditPurchase> purchases = purchaseRepo.findInRange(start, end);
        List<AdminPaymentRowDto> rows = purchases.stream()
            .filter(p -> filter == AdminPaymentFilter.ALL || p.getStatus() == CreditPurchaseStatus.APPROVED)
            .map(AdminPaymentService::toRow)
            .toList();
        return new AdminPaymentReportDto(rows, summarize(purchases));
    }

    private static AdminPaymentSummaryDto summarize(List<CreditPurchase> purchases) {
        long approved = 0;
        long gross = 0;
        long fee = 0;
        long net = 0;
        long withoutFee = 0;
        long mediation = 0;
        long reversed = 0;
        long[] kindCount = new long[KINDS.size()];
        long[] kindGross = new long[KINDS.size()];
        for (CreditPurchase p : purchases) {
            switch (p.getStatus()) {
                case IN_MEDIATION -> mediation++;
                case REVERSED -> reversed++;
                case APPROVED -> {
                    approved++;
                    gross += p.getAmountCents();
                    if (p.getMpFeeCents() == null || p.getMpNetReceivedCents() == null) {
                        withoutFee++;
                    }
                    fee += p.getMpFeeCents() == null ? 0 : p.getMpFeeCents();
                    net += p.getMpNetReceivedCents() == null ? 0 : p.getMpNetReceivedCents();
                    int i = KINDS.indexOf(kindOf(p));
                    kindCount[i]++;
                    kindGross[i] += p.getAmountCents();
                }
                default -> { }
            }
        }
        List<AdminPaymentSummaryDto.KindBreakdown> byKind = new ArrayList<>();
        for (int i = 0; i < KINDS.size(); i++) {
            byKind.add(new AdminPaymentSummaryDto.KindBreakdown(KINDS.get(i), kindCount[i], kindGross[i]));
        }
        return new AdminPaymentSummaryDto(approved, gross, fee, net, withoutFee, byKind, mediation, reversed);
    }

    private static AdminPaymentRowDto toRow(CreditPurchase p) {
        return new AdminPaymentRowDto(
            p.getId(),
            p.getCreditedAt() != null ? p.getCreditedAt() : p.getCreatedAt(),
            p.getUser().displayName(),
            kindOf(p),
            p.getPack() == null ? null : p.getPack().getNombre(),
            p.getOrder() == null ? null : p.getOrder().getId(),
            p.getCreditAmount(),
            p.getAmountCents(),
            p.getStatus(),
            p.getMpPaymentId(),
            p.getMpFeeCents(),
            p.getMpNetReceivedCents(),
            p.getCreditsReversed());
    }

    private static String kindOf(CreditPurchase p) {
        if (p.getType() == PurchaseType.DIRECT || p.getPack() == null) {
            return DIRECT;
        }
        return p.getPack().getPackType().name();
    }
}
