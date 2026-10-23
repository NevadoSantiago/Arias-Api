package com.arias.printing;

import com.arias.catalog.dishes.DishStation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Print status per order for the kitchen board. Only the latest non-cancelled job of each station
 * counts, so a reprint replaces the previous attempt. Any FAILED wins, then any unfinished job
 * (QUEUED), otherwise PRINTED.
 */
@Service
@RequiredArgsConstructor
public class PrintStatusService {

    private final PrintJobRepository jobRepo;

    /** Summary for every given order id; orders without jobs map to {@link PrintSummary#NONE}. */
    @Transactional(readOnly = true)
    public Map<Long, PrintSummary> summarize(Collection<Long> orderIds) {
        Map<Long, PrintSummary> result = new HashMap<>();
        if (orderIds == null || orderIds.isEmpty()) {
            return result;
        }
        Map<Long, List<PrintJobInfo>> byOrder = jobRepo.findInfoByOrderIds(orderIds).stream()
            .collect(Collectors.groupingBy(PrintJobInfo::orderId));
        for (Long id : orderIds) {
            result.put(id, summarizeOrder(byOrder.getOrDefault(id, List.of())));
        }
        return result;
    }

    static PrintSummary summarizeOrder(List<PrintJobInfo> jobs) {
        Map<DishStation, PrintJobInfo> latest = jobs.stream().collect(Collectors.toMap(
            PrintJobInfo::station, j -> j, (a, b) -> a.jobId() > b.jobId() ? a : b));
        if (latest.isEmpty()) {
            return PrintSummary.NONE;
        }
        if (latest.values().stream().anyMatch(j -> j.status() == PrintJobStatus.FAILED)) {
            return new PrintSummary(PrintStatus.FAILED, null);
        }
        if (latest.values().stream().anyMatch(j -> j.status() != PrintJobStatus.PRINTED)) {
            return new PrintSummary(PrintStatus.QUEUED, null);
        }
        Instant printedAt = latest.values().stream()
            .map(PrintJobInfo::printedAt)
            .filter(java.util.Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
        return new PrintSummary(PrintStatus.PRINTED, printedAt);
    }
}
