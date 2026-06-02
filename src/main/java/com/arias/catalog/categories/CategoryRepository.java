package com.arias.catalog.categories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    @Query("SELECT c FROM Category c WHERE c.nombre = :nombre AND c.deletedAt IS NULL")
    Optional<Category> findByNombre(@Param("nombre") String nombre);

    @Query("""
        SELECT c FROM Category c
        WHERE c.enabled = true AND c.deletedAt IS NULL
        ORDER BY c.ordenDisplay ASC
    """)
    List<Category> findAllByEnabledTrueOrderByOrdenDisplayAsc();

    @Query("SELECT c FROM Category c WHERE c.deletedAt IS NULL")
    List<Category> findAllNotDeleted();
}
