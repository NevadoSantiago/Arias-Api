package com.arias.metrics;

import com.arias.common.exception.BusinessException;
import com.arias.common.security.JwtUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/company-admin/metrics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('COMPANY_ADMIN')")
public class CompanyAdminMetricsController {

    private final CompanyAdminMetricsService service;

    @GetMapping("/daily-orders")
    public List<DailyOrderCount> dailyOrders(@AuthenticationPrincipal JwtUser user) {
        return service.dailyOrders(ensureCompany(user));
    }

    @GetMapping("/orders-by-category")
    public List<CategoryOrderCount> ordersByCategory(@AuthenticationPrincipal JwtUser user) {
        return service.ordersByCategory(ensureCompany(user));
    }

    @GetMapping("/participation")
    public ParticipationMetrics participation(@AuthenticationPrincipal JwtUser user) {
        return service.participation(ensureCompany(user));
    }

    private Long ensureCompany(JwtUser user) {
        if (user.companyId() == null) {
            throw BusinessException.forbidden("no-company",
                "Tu usuario no tiene empresa asignada");
        }
        return user.companyId();
    }
}
