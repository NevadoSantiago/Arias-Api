package com.arias.companies;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByCuit(String cuit);

    List<Company> findAllByEnabledTrueOrderByNombreAsc();
}
