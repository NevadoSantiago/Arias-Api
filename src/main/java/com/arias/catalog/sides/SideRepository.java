package com.arias.catalog.sides;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SideRepository extends JpaRepository<Side, Long> {

    List<Side> findAllByEnabledTrueAndDeletedAtIsNullOrderByTipoAscNombreAsc();

    List<Side> findAllByTipoAndEnabledTrueAndDeletedAtIsNullOrderByNombreAsc(SideType tipo);

    /**
     * Desasocia el side de TODOS los platos. Usado al resucitar un side
     * archivado con otro tipo: las asociaciones viejas serían de un
     * {@link SideType} que ya no corresponde.
     */
    @Modifying
    @Query(value = "DELETE FROM dish_side WHERE side_id = :sideId", nativeQuery = true)
    void removeFromAllDishes(@Param("sideId") Long sideId);
}
