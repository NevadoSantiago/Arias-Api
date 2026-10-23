package com.arias.printing;

import com.arias.catalog.dishes.DishStation;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** Network ticket printer of a kitchen station (raw TCP, usually port 9100). One row per station. */
@Entity
@Table(name = "printer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Printer {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DishStation station;

    @Column(nullable = false, length = 45)
    private String host;

    @Column(nullable = false)
    private Integer port;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
