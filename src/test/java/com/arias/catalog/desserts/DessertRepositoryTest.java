package com.arias.catalog.desserts;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Atomic stock operations of {@link DessertRepository}. */
@SpringBootTest
@Transactional
class DessertRepositoryTest {

    @Autowired private DessertRepository repo;
    @Autowired private EntityManager em;

    private Dessert save(int daily, int current, boolean enabled, boolean deleted) {
        Dessert d = repo.saveAndFlush(Dessert.builder()
            .nombre("Flan " + System.nanoTime())
            .stockDiarioDefault(daily)
            .stockActual(current)
            .enabled(enabled)
            .deletedAt(deleted ? Instant.now() : null)
            .build());
        em.clear();
        return d;
    }

    private int stockOf(Dessert d) {
        em.clear();
        return repo.findById(d.getId()).orElseThrow().getStockActual();
    }

    @Test
    @DisplayName("decrementStock removes one unit while there is stock")
    void decrementWithStock() {
        Dessert d = save(5, 2, true, false);

        assertThat(repo.decrementStock(d.getId())).isEqualTo(1);
        assertThat(stockOf(d)).isEqualTo(1);
    }

    @Test
    @DisplayName("decrementStock at zero updates nothing and never goes negative")
    void decrementAtZero() {
        Dessert d = save(5, 0, true, false);

        assertThat(repo.decrementStock(d.getId())).isZero();
        assertThat(stockOf(d)).isZero();
    }

    @Test
    @DisplayName("incrementStock adds one unit")
    void increment() {
        Dessert d = save(5, 0, true, false);

        assertThat(repo.incrementStock(d.getId())).isEqualTo(1);
        assertThat(stockOf(d)).isEqualTo(1);
    }

    @Test
    @DisplayName("resetAllStock restores the daily default only for enabled, non-deleted desserts")
    void resetAll() {
        Dessert active = save(7, 1, true, false);
        Dessert disabled = save(7, 1, false, false);
        Dessert deleted = save(7, 1, true, true);

        repo.resetAllStock();

        assertThat(stockOf(active)).isEqualTo(7);
        assertThat(stockOf(disabled)).isEqualTo(1);
        assertThat(stockOf(deleted)).isEqualTo(1);
    }
}
