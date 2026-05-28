package com.arias.restaurantconfig;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RestaurantConfigRepository extends JpaRepository<RestaurantConfig, Long> {

    /** Atajo para leer el singleton — siempre id=1. */
    default RestaurantConfig getSingleton() {
        return findById(1L).orElseThrow(() ->
            new IllegalStateException("RestaurantConfig singleton no encontrado — chequear seed inicial"));
    }
}
