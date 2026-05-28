package com.arias.catalog.categories;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByNombre(String nombre);

    List<Category> findAllByEnabledTrueOrderByOrdenDisplayAsc();
}
