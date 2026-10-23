package com.arias.printing;

import com.arias.catalog.dishes.DishStation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrinterRepository extends JpaRepository<Printer, DishStation> {
}
