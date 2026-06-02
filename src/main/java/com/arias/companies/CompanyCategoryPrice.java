package com.arias.companies;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Precio acordado entre una empresa y una categoría (tier de plato).
 * Composite key (companyId, categoryId). Precio en pesos sin decimales.
 */
@Entity
@Table(name = "company_category_price")
@IdClass(CompanyCategoryPriceId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyCategoryPrice {

    @Id
    @Column(name = "company_id")
    private Long companyId;

    @Id
    @Column(name = "category_id")
    private Long categoryId;

    @Column(nullable = false)
    private Integer precio;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
