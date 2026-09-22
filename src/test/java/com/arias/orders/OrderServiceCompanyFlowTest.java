package com.arias.orders;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.exception.BusinessException;
import com.arias.companies.Company;
import com.arias.companies.CompanyCategoryPrice;
import com.arias.companies.CompanyCategoryPriceRepository;
import com.arias.companies.CompanyRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unidad 6 — RED: protección del flujo de pedidos de empresa (B2B).
 *
 * <p>Caracteriza el comportamiento de {@link OrderService#place}, {@link
 * OrderService#update}, {@link OrderService#cancel} y el consolidado
 * consumido por {@code AdminOrderController} EXACTAMENTE como funcionan hoy,
 * sobre el modelo {@link DailyChoice}. Ninguno de estos tests debería
 * cambiar de resultado por escribirse — pinean la realidad actual, no el
 * diseño futuro de la unidad 7 (que reemplaza este camino por {@code Order}
 * + consumo de créditos). Si la unidad 7 introduce una regresión en el
 * camino B2B, uno de estos tests debe fallar.
 *
 * <p>Igual que {@code CreditLedgerServiceTest} y {@code RegistrationServiceTest},
 * levanta el contexto de Spring completo contra la base real de Docker, con
 * un {@link Clock} fijo (09:00, antes del {@code hora_corte} 10:00 seedeado
 * por V2) para que el pedido de "hoy" decremente stock de forma determinística.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(OrderServiceCompanyFlowTest.FixedClockConfig.class)
class OrderServiceCompanyFlowTest {

    static final Instant FIXED_NOW = Instant.parse("2026-02-10T12:00:00Z"); // 09:00 ART, antes del corte (10:00)

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZoneId.of("America/Argentina/Buenos_Aires"));
        }
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private DailyChoiceRepository orderRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private CompanyRepository companyRepo;

    @Autowired
    private CategoryRepository categoryRepo;

    @Autowired
    private MenuSectionRepository menuSectionRepo;

    @Autowired
    private DishRepository dishRepo;

    @Autowired
    private CompanyCategoryPriceRepository priceRepo;

    /**
     * {@code DishRepository.decrementStock/incrementStock} son bulk JPQL
     * {@code UPDATE} sin {@code clearAutomatically}: no refrescan el
     * first-level cache de Hibernate. Dentro de la MISMA transacción de
     * test, releer el {@link Dish} vía {@code findById} devolvería la
     * entidad gestionada vieja si no se limpia el contexto de persistencia
     * primero. Es un detalle de este test, no de {@code OrderService}.
     */
    @Autowired
    private EntityManager entityManager;

    private static LocalDate today() {
        return LocalDate.now(Clock.fixed(FIXED_NOW, ZoneId.of("America/Argentina/Buenos_Aires")));
    }

    private Category persistCategory(String prefix) {
        Category category = Category.builder()
            .nombre(prefix + "-" + System.nanoTime())
            .ordenDisplay(0)
            .enabled(true)
            .creditCost(1)
            .build();
        return categoryRepo.save(category);
    }

    private MenuSection persistMenuSection() {
        MenuSection section = MenuSection.builder()
            .nombre("seccion-" + System.nanoTime())
            .ordenDisplay(0)
            .enabled(true)
            .build();
        return menuSectionRepo.save(section);
    }

    private Company persistCompany(Category categoriaDefault, LocalTime horaEntrega) {
        Company company = Company.builder()
            .nombre("Empresa-" + System.nanoTime())
            .cuit(String.valueOf(20000000000L + (System.nanoTime() % 9000000000L)))
            .calle("Calle Falsa")
            .altura("123")
            .horaEntrega(horaEntrega)
            .categoriaDefault(categoriaDefault)
            .enabled(true)
            .build();
        return companyRepo.save(company);
    }

    private Dish persistDish(Category category, MenuSection section, int stock) {
        Dish dish = Dish.builder()
            .nombre("Plato-" + System.nanoTime())
            .category(category)
            .menuSection(section)
            .enabled(true)
            .especial(false)
            .stockDiarioDefault(stock)
            .stockActual(stock)
            .build();
        return dishRepo.save(dish);
    }

    private User persistEmployee(Company company, Category category) {
        User user = User.builder()
            .email("empleado-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .company(company)
            .category(category)
            .active(true)
            .build();
        return userRepo.save(user);
    }

    // ─── place(): CompanyCategoryPrice lookup + precioSnapshot congelado ──

    @Test
    @DisplayName("place(): congela precioSnapshot desde CompanyCategoryPrice y horaEntrega desde la empresa")
    void placeCongelaPrecioYHoraEntregaDeLaEmpresa() {
        MenuSection section = persistMenuSection();
        Category category = persistCategory("Premium");
        LocalTime horaEntrega = LocalTime.of(13, 30);
        Company company = persistCompany(category, horaEntrega);
        priceRepo.save(CompanyCategoryPrice.builder()
            .companyId(company.getId())
            .categoryId(category.getId())
            .precio(8000)
            .build());
        Dish dish = persistDish(category, section, 5);
        User employee = persistEmployee(company, category);

        DailyChoice order = orderService.place(employee.getId(),
            new PlaceOrderRequest(dish.getId(), null, null, null));

        assertThat(order.getPrecioSnapshot()).isEqualTo(8000);
        assertThat(order.getHoraEntrega()).isEqualTo(horaEntrega);
        assertThat(order.getCompany().getId()).isEqualTo(company.getId());
        assertThat(order.getDishNombre()).isEqualTo(dish.getNombre());
        assertThat(order.getDishCategoria()).isEqualTo(category.getNombre());
        assertThat(order.getCategory().getId()).isEqualTo(category.getId());
        assertThat(order.getEstado()).isEqualTo(OrderEstado.PENDIENTE);
    }

    @Test
    @DisplayName("place(): decrementa stock atómicamente para pedidos de hoy")
    void placeDecrementaStockDelDiaDeHoy() {
        MenuSection section = persistMenuSection();
        Category category = persistCategory("Basico");
        Company company = persistCompany(category, LocalTime.of(12, 0));
        priceRepo.save(CompanyCategoryPrice.builder()
            .companyId(company.getId()).categoryId(category.getId()).precio(5000).build());
        Dish dish = persistDish(category, section, 3);
        User employee = persistEmployee(company, category);

        orderService.place(employee.getId(), new PlaceOrderRequest(dish.getId(), null, null, null));

        entityManager.clear();
        Dish reloaded = dishRepo.findById(dish.getId()).orElseThrow();
        assertThat(reloaded.getStockActual()).isEqualTo(2);
    }

    @Test
    @DisplayName("place(): un empleado no puede tener dos pedidos el mismo día")
    void placeRechazaSegundoPedidoElMismoDia() {
        MenuSection section = persistMenuSection();
        Category category = persistCategory("Premium");
        Company company = persistCompany(category, LocalTime.of(12, 0));
        Dish dish = persistDish(category, section, 5);
        User employee = persistEmployee(company, category);

        orderService.place(employee.getId(), new PlaceOrderRequest(dish.getId(), null, null, null));

        assertThatThrownBy(() -> orderService.place(employee.getId(),
            new PlaceOrderRequest(dish.getId(), null, null, null)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "order-already-exists");

        assertThat(orderRepo.findByUserIdAndFecha(employee.getId(), today())).isPresent();
    }

    // ─── Tarifa faltante: no bloquea, cae a 0 ─────────────────────────────

    @Test
    @DisplayName("place(): sin CompanyCategoryPrice configurado, precioSnapshot cae a 0 y NO bloquea el pedido")
    void placeSinTarifaConfiguradaCaeACeroYNoBloquea() {
        MenuSection section = persistMenuSection();
        Category category = persistCategory("SinTarifa");
        Company company = persistCompany(category, LocalTime.of(12, 0));
        // Deliberadamente sin CompanyCategoryPrice para este company × category.
        Dish dish = persistDish(category, section, 5);
        User employee = persistEmployee(company, category);

        DailyChoice order = orderService.place(employee.getId(),
            new PlaceOrderRequest(dish.getId(), null, null, null));

        assertThat(order.getPrecioSnapshot()).isZero();
        assertThat(order.getId()).isNotNull();
    }

    // ─── update() ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("update(): cambiar de plato devuelve stock al plato viejo, decrementa el nuevo y recongela precio/categoría")
    void updateCambiaDePlatoYRecongelaSnapshots() {
        MenuSection section = persistMenuSection();
        Category categoriaVieja = persistCategory("Vieja");
        Category categoriaNueva = persistCategory("Nueva");
        Company company = persistCompany(categoriaVieja, LocalTime.of(12, 0));
        priceRepo.save(CompanyCategoryPrice.builder()
            .companyId(company.getId()).categoryId(categoriaVieja.getId()).precio(4000).build());
        priceRepo.save(CompanyCategoryPrice.builder()
            .companyId(company.getId()).categoryId(categoriaNueva.getId()).precio(9000).build());
        Dish dishViejo = persistDish(categoriaVieja, section, 5);
        Dish dishNuevo = persistDish(categoriaNueva, section, 5);
        User employee = persistEmployee(company, categoriaVieja);

        DailyChoice placed = orderService.place(employee.getId(),
            new PlaceOrderRequest(dishViejo.getId(), null, null, null));
        assertThat(placed.getPrecioSnapshot()).isEqualTo(4000);

        DailyChoice updated = orderService.update(employee.getId(), placed.getId(),
            new PlaceOrderRequest(dishNuevo.getId(), null, null, null));

        assertThat(updated.getDish().getId()).isEqualTo(dishNuevo.getId());
        assertThat(updated.getPrecioSnapshot()).isEqualTo(9000);
        assertThat(updated.getCategory().getId()).isEqualTo(categoriaNueva.getId());

        entityManager.clear();
        assertThat(dishRepo.findById(dishViejo.getId()).orElseThrow().getStockActual()).isEqualTo(5);
        assertThat(dishRepo.findById(dishNuevo.getId()).orElseThrow().getStockActual()).isEqualTo(4);
    }

    // ─── cancel(): restaura stock, borra el pedido ────────────────────────

    @Test
    @DisplayName("cancel(): restaura el stock del plato y borra el pedido")
    void cancelRestauraStockYBorraElPedido() {
        MenuSection section = persistMenuSection();
        Category category = persistCategory("Cancelable");
        Company company = persistCompany(category, LocalTime.of(12, 0));
        Dish dish = persistDish(category, section, 2);
        User employee = persistEmployee(company, category);

        DailyChoice placed = orderService.place(employee.getId(),
            new PlaceOrderRequest(dish.getId(), null, null, null));
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(1);

        orderService.cancel(employee.getId(), placed.getId());

        // cancel() hace entityManager.remove(existing) — a diferencia de los
        // UPDATE en bloque de arriba, el DELETE queda diferido hasta el
        // próximo flush. Sin flush() explícito, clear() lo descartaría sin
        // llegar nunca a ejecutarse contra la base.
        entityManager.flush();
        entityManager.clear();
        assertThat(dishRepo.findById(dish.getId()).orElseThrow().getStockActual()).isEqualTo(2);
        assertThat(orderRepo.findById(placed.getId())).isEmpty();
        assertThat(orderRepo.findByUserIdAndFecha(employee.getId(), today())).isEmpty();
    }

    // ─── Consolidado por empresa (AdminOrderController) ───────────────────

    @Test
    @DisplayName("el pedido de un empleado sigue apareciendo agrupado por companyId en el consolidado del admin")
    void pedidoDeEmpleadoApareceAgrupadoPorEmpresaEnElConsolidado() {
        MenuSection section = persistMenuSection();
        Category category = persistCategory("Admin");
        Company company = persistCompany(category, LocalTime.of(12, 30));
        Dish dish = persistDish(category, section, 5);
        User employee = persistEmployee(company, category);

        DailyChoice placed = orderService.place(employee.getId(),
            new PlaceOrderRequest(dish.getId(), null, null, null));

        // Mismas queries que respaldan GET /api/v1/admin/orders/today y el
        // listado por empresa del panel de CompanyAdmin.
        assertThat(orderRepo.findAllByFechaOrderByCompanyIdAscHoraEntregaAsc(today()))
            .extracting(DailyChoice::getId)
            .contains(placed.getId());

        assertThat(orderRepo.findAllByCompanyIdAndFechaOrderByHoraEntregaAsc(company.getId(), today()))
            .extracting(DailyChoice::getId)
            .containsExactly(placed.getId());
    }

    @Test
    @DisplayName("markComandadoByCompany/markDeliveredByCompany avanzan el estado del pedido de empresa (base de export/deliver del admin)")
    void marcarComandadoYEntregadoPorEmpresaAvanzaElEstado() {
        MenuSection section = persistMenuSection();
        Category category = persistCategory("Fulfillment");
        Company company = persistCompany(category, LocalTime.of(12, 30));
        Dish dish = persistDish(category, section, 5);
        User employee = persistEmployee(company, category);

        DailyChoice placed = orderService.place(employee.getId(),
            new PlaceOrderRequest(dish.getId(), null, null, null));

        // El corte (OrdersScheduler) pasa PENDIENTE -> CONFIRMADO antes de que
        // el export/deliver del admin tenga sentido — se simula directamente.
        orderRepo.closeAllPendingForDate(today(), FIXED_NOW);

        int comandados = orderService.markComandadoByCompany(company.getId(), today());
        assertThat(comandados).isEqualTo(1);
        entityManager.clear();
        assertThat(orderRepo.findById(placed.getId()).orElseThrow().getEstado())
            .isEqualTo(OrderEstado.COMANDADO);

        int entregados = orderService.markDeliveredByCompany(company.getId());
        assertThat(entregados).isEqualTo(1);
        entityManager.clear();
        assertThat(orderRepo.findById(placed.getId()).orElseThrow().getEstado())
            .isEqualTo(OrderEstado.ENTREGADO);
    }
}
