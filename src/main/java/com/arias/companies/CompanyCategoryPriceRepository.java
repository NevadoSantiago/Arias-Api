package com.arias.companies;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CompanyCategoryPriceRepository
    extends JpaRepository<CompanyCategoryPrice, CompanyCategoryPriceId> {

    List<CompanyCategoryPrice> findByCompanyId(Long companyId);

    List<CompanyCategoryPrice> findByCategoryId(Long categoryId);

    @Modifying
    @Query("DELETE FROM CompanyCategoryPrice p WHERE p.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);

    @Modifying
    @Query("DELETE FROM CompanyCategoryPrice p WHERE p.categoryId = :categoryId")
    void deleteByCategoryId(@Param("categoryId") Long categoryId);
}
