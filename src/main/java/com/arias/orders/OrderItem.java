package com.arias.orders;

import com.arias.catalog.categories.Category;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.sides.Side;
import jakarta.persistence.*;
import lombok.*;

/**
 * Ítem de un {@link Order} — reemplaza a {@code precioSnapshot} de {@link
 * DailyChoice}: en vez de un precio congelado de empresa × categoría, cada
 * ítem congela su propio {@code creditCost} tomado de {@code
 * Category.creditCost} al momento del pedido (unidad 7, diseño §Decisión 2).
 *
 * <p>{@code dishNombre}/{@code dishCategoria}/{@code sideNombre} son
 * snapshots inmutables — igual que en {@code DailyChoice}, no se actualizan
 * aunque cambien las entidades referenciadas.
 */
@Entity
@Table(name = "order_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_id", nullable = false)
    private Dish dish;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "side_id")
    private Side side;

    /** Categoría (tier) del plato al momento del pedido — de acá sale {@code creditCost}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    // ── Snapshots inmutables ──────────────────────────────────────────
    @Column(name = "dish_nombre", nullable = false, length = 150)
    private String dishNombre;

    @Column(name = "dish_categoria", nullable = false, length = 100)
    private String dishCategoria;

    @Column(name = "side_nombre", length = 100)
    private String sideNombre;

    /** Costo en créditos congelado desde {@code Category.creditCost} al momento del pedido. */
    @Column(name = "credit_cost", nullable = false)
    private Integer creditCost;

    @Column(columnDefinition = "TEXT")
    private String notas;
}
