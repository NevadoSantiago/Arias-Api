package com.arias.metrics;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.orders.DailyChoiceRepository;
import com.arias.users.Role;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanyAdminMetricsService {

    private final DailyChoiceRepository dailyChoiceRepo;
    private final CategoryRepository categoryRepo;
    private final UserRepository userRepo;

    public List<DailyOrderCount> dailyOrders(Long companyId) {
        LocalDate since = LocalDate.now().minusDays(29);

        Map<LocalDate, Long> countsByDate = dailyChoiceRepo
            .countDailyByCompany(companyId, since)
            .stream()
            .collect(Collectors.toMap(
                row -> (LocalDate) row[0],
                row -> (Long) row[1]
            ));

        List<DailyOrderCount> result = new ArrayList<>(30);
        for (LocalDate d = since; !d.isAfter(LocalDate.now()); d = d.plusDays(1)) {
            result.add(new DailyOrderCount(d, countsByDate.getOrDefault(d, 0L)));
        }
        return result;
    }

    public List<CategoryOrderCount> ordersByCategory(Long companyId) {
        LocalDate since = LocalDate.now().minusDays(29);

        Map<String, Long> countsByCat = dailyChoiceRepo
            .countByCategoryForCompany(companyId, since)
            .stream()
            .collect(Collectors.toMap(
                row -> (String) row[0],
                row -> (Long) row[1]
            ));

        List<Category> allCategories = categoryRepo.findAll();

        return allCategories.stream()
            .map(cat -> new CategoryOrderCount(
                cat.getId(),
                cat.getNombre(),
                countsByCat.getOrDefault(cat.getNombre(), 0L)
            ))
            .sorted(Comparator.comparing(CategoryOrderCount::categoryName))
            .toList();
    }

    public ParticipationMetrics participation(Long companyId) {
        LocalDate today = LocalDate.now();

        long activeEmployees = userRepo.countActiveEmployeesByCompany(companyId);
        long orderedToday = dailyChoiceRepo.countByCompanyAndFecha(companyId, today);

        double todayRate = activeEmployees > 0
            ? (double) orderedToday / activeEmployees
            : 0.0;

        LocalDate monday = today.with(DayOfWeek.MONDAY);
        long daysElapsed = (long) today.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue() + 1;

        long weekOrders = 0;
        for (LocalDate d = monday; !d.isAfter(today); d = d.plusDays(1)) {
            weekOrders += dailyChoiceRepo.countByCompanyAndFecha(companyId, d);
        }

        double weekRate = activeEmployees > 0
            ? ((double) weekOrders / daysElapsed) / activeEmployees
            : 0.0;

        return new ParticipationMetrics(
            activeEmployees,
            orderedToday,
            Math.round(todayRate * 10000) / 10000.0,
            weekOrders,
            Math.round(weekRate * 10000) / 10000.0
        );
    }
}
