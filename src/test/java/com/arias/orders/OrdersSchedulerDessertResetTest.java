package com.arias.orders;

import com.arias.catalog.desserts.Dessert;
import com.arias.catalog.desserts.DessertRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** The midnight job also restores the daily stock of desserts. */
@SpringBootTest
@Transactional
class OrdersSchedulerDessertResetTest {

    @Autowired private OrdersScheduler scheduler;
    @Autowired private DessertRepository dessertRepo;
    @Autowired private EntityManager em;

    @Test
    @DisplayName("resetDailyStock restores dessert stock to its daily default")
    void resetsDesserts() {
        Dessert d = dessertRepo.saveAndFlush(Dessert.builder()
            .nombre("Flan " + System.nanoTime())
            .stockDiarioDefault(6)
            .stockActual(0)
            .enabled(true)
            .build());

        scheduler.resetDailyStock();

        em.clear();
        assertThat(dessertRepo.findById(d.getId()).orElseThrow().getStockActual()).isEqualTo(6);
    }
}
