package com.arias.restaurantconfig;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PickupScheduleRepository extends JpaRepository<PickupSchedule, Integer> {

    /** Los 7 días, ordenados lunes (1) a domingo (7). */
    List<PickupSchedule> findAllByOrderByDayOfWeekAsc();

    Optional<PickupSchedule> findByDayOfWeek(Integer dayOfWeek);

    /** Atajo: falla claro si falta la fila de un día — los 7 se siembran en V24. */
    default PickupSchedule getByDayOfWeek(int dayOfWeek) {
        return findByDayOfWeek(dayOfWeek).orElseThrow(() ->
            new IllegalStateException("PickupSchedule sin fila para day_of_week=" + dayOfWeek
                + " — chequear seed de V24"));
    }
}
