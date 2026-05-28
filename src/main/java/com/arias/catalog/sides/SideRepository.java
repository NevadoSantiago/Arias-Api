package com.arias.catalog.sides;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SideRepository extends JpaRepository<Side, Long> {

    List<Side> findAllByEnabledTrueOrderByTipoAscNombreAsc();

    List<Side> findAllByTipoAndEnabledTrueOrderByNombreAsc(SideType tipo);
}
