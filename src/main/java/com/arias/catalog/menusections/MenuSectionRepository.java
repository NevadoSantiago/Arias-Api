package com.arias.catalog.menusections;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuSectionRepository extends JpaRepository<MenuSection, Long> {

    Optional<MenuSection> findByNombre(String nombre);

    List<MenuSection> findAllByEnabledTrueOrderByOrdenDisplayAsc();
}
