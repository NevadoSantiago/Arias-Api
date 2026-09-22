package com.arias.orders;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.common.exception.BusinessException;
import com.arias.common.security.JwtUser;
import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unidad 13 — {@link AdminOrderController}: spec {@code
 * admin-order-fulfillment}. Cubre las dos vistas nuevas por horario de
 * retiro (tabla {@code orders}) Y confirma, como regresión, que las vistas
 * existentes por empresa (tabla {@code daily_choice}) siguen funcionando sin
 * cambio de comportamiento — ambas fuentes de datos nunca se mezclan en un
 * mismo endpoint.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(AdminOrderControllerTest.FixedClockConfig.class)
class AdminOrderControllerTest {

    // 2026-03-10 10:00 ART (UTC-3) — dentro de la ventana de pedidos por defecto (11:00-15:00 ART).
    static final Instant FIXED_NOW = Instant.parse("2026-03-10T13:00:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDate TODAY = LocalDate.ofInstant(FIXED_NOW, ZONE);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired
    private AdminOrderController controller;

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private DailyChoiceRepository dailyChoiceRepo;

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
    private EntityManager entityManager;

    @BeforeEach
    void authenticateAsSuperAdmin() {
        JwtUser principal = new JwtUser(1L, "admin@arias.com", Role.SUPER_ADMIN, null, null);
        var authority = new SimpleGrantedAuthority("ROLE_SUPER_ADMIN");
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(authority)));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─── Fixtures ────────────────────────────────────────────────────────

    private Category persistCategory(int creditCost) {
        return categoryRepo.save(Category.builder()
            .nombre("Categoria-" + System.nanoTime())
            .ordenDisplay(0)
            .enabled(true)
            .creditCost(creditCost)
            .build());
    }

    private MenuSection persistMenuSection() {
        return menuSectionRepo.save(MenuSection.builder()
            .nombre("seccion-" + System.nanoTime())
            .ordenDisplay(0)
            .enabled(true)
            .build());
    }

    private Dish persistDish(Category category, MenuSection section) {
        return dishRepo.save(Dish.builder()
            .nombre("Plato-" + System.nanoTime())
            .category(category)
            .menuSection(section)
            .enabled(true)
            .especial(false)
            .stockDiarioDefault(50)
            .stockActual(50)
            .build());
    }

    private User persistB2cUser(String nickname) {
        return userRepo.save(User.builder()
            .email("b2c-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .nickname(nickname)
            .active(true)
            .build());
    }

    private Company persistCompany(Category categoriaDefault) {
        return companyRepo.save(Company.builder()
            .nombre("Empresa-" + System.nanoTime())
            .cuit(String.valueOf(20000000000L + (System.nanoTime() % 9000000000L)))
            .calle("Calle Falsa")
            .altura("123")
            .horaEntrega(LocalTime.of(13, 0))
            .categoriaDefault(categoriaDefault)
            .enabled(true)
            .build());
    }

    private User persistEmployee(Company company, Category category) {
        return userRepo.save(User.builder()
            .email("empleado-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .firstName("Juan")
            .lastName("Perez")
            .company(company)
            .category(category)
            .active(true)
            .build());
    }

    private Order persistOrder(User user, Dish dish, Instant pickupAt, OrderEstado estado, String notas) {
        Order order = Order.builder()
            .user(user)
            .company(user.getCompany())
            .fecha(LocalDate.ofInstant(pickupAt, ZONE))
            .pickupAt(pickupAt)
            .estado(estado)
            .creditTotal(dish.getCategory().getCreditCost())
            .notas(notas)
            .cancelledAt(estado == OrderEstado.CANCELADO ? FIXED_NOW : null)
            .build();
        order.addItem(OrderItem.builder()
            .dish(dish)
            .category(dish.getCategory())
            .dishNombre(dish.getNombre())
            .dishCategoria(dish.getCategory().getNombre())
            .creditCost(dish.getCategory().getCreditCost())
            .build());
        return orderRepo.save(order);
    }

    private DailyChoice persistCompanyChoice(Company company, User user, Dish dish, OrderEstado estado) {
        return dailyChoiceRepo.save(DailyChoice.builder()
            .user(user)
            .company(company)
            .fecha(TODAY)
            .dish(dish)
            .estado(estado)
            .dishNombre(dish.getNombre())
            .dishCategoria(dish.getCategory().getNombre())
            .category(dish.getCategory())
            .horaEntrega(LocalTime.of(13, 0))
            .precioSnapshot(500)
            .confirmedAt(estado == OrderEstado.PENDIENTE ? null : FIXED_NOW)
            .build());
    }

    // ─── Listado agrupado por horario de retiro (spec: "Listado agrupado por horario") ──

    @Test
    @DisplayName("getOrdersByPickup(): agrupa los pedidos del día por horario de retiro")
    void getOrdersByPickupAgrupaPorHorario() {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section);

        User cliente1 = persistB2cUser("Coty");
        User cliente2 = persistB2cUser("Fede");

        Instant pickup1100 = FIXED_NOW.plus(60, ChronoUnit.MINUTES); // 11:00 ART
        Instant pickup1200 = FIXED_NOW.plus(120, ChronoUnit.MINUTES); // 12:00 ART

        persistOrder(cliente1, dish, pickup1100, OrderEstado.PENDIENTE, null);
        persistOrder(cliente2, dish, pickup1100, OrderEstado.PENDIENTE, "sin sal");
        persistOrder(cliente1, dish, pickup1200, OrderEstado.CONFIRMADO, null);

        List<AdminOrderDto.PickupGroupDto> groups = controller.getOrdersByPickup(TODAY);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).pickupTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(groups.get(0).orders()).hasSize(2);
        assertThat(groups.get(1).pickupTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(groups.get(1).orders()).hasSize(1);

        AdminOrderDto.PickupOrderDto row = groups.get(0).orders().stream()
            .filter(o -> "Coty".equals(o.customerNickname()))
            .findFirst().orElseThrow();
        assertThat(row.items()).hasSize(1);
        assertThat(row.items().get(0).dishNombre()).isEqualTo(dish.getNombre());
    }

    @Test
    @DisplayName("getOrdersByPickup(): cae a nombre y apellido cuando el usuario no tiene apodo")
    void getOrdersByPickupCaeANombreYApellidoSinApodo() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section);
        Company company = persistCompany(category);
        User employee = persistEmployee(company, category);

        persistOrder(employee, dish, FIXED_NOW.plus(60, ChronoUnit.MINUTES), OrderEstado.PENDIENTE, null);

        List<AdminOrderDto.PickupGroupDto> groups = controller.getOrdersByPickup(TODAY);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).orders().get(0).customerNickname()).isEqualTo("Juan Perez");
    }

    @Test
    @DisplayName("getOrdersByPickup(): excluye pedidos CANCELADO")
    void getOrdersByPickupExcluyeCancelados() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section);
        User cliente = persistB2cUser("Coty");

        persistOrder(cliente, dish, FIXED_NOW.plus(60, ChronoUnit.MINUTES), OrderEstado.CANCELADO, null);

        List<AdminOrderDto.PickupGroupDto> groups = controller.getOrdersByPickup(TODAY);

        assertThat(groups).isEmpty();
    }

    @Test
    @DisplayName("getOrdersByPickup(): no mezcla pedidos de la tabla daily_choice (empresa)")
    void getOrdersByPickupNoMezclaConDailyChoice() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section);
        Company company = persistCompany(category);
        User employee = persistEmployee(company, category);

        persistCompanyChoice(company, employee, dish, OrderEstado.CONFIRMADO);

        List<AdminOrderDto.PickupGroupDto> groups = controller.getOrdersByPickup(TODAY);

        assertThat(groups).isEmpty();
    }

    // ─── Exportación agrupada por horario (spec: "Exportación agrupada por horario") ──

    @Test
    @DisplayName("exportOrdersByPickup(): exporta un .xlsx con los pedidos agrupados por horario de retiro")
    void exportOrdersByPickupGeneraExcelAgrupado() throws Exception {
        Category category = persistCategory(2);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section);
        User cliente = persistB2cUser("Coty");

        persistOrder(cliente, dish, FIXED_NOW.plus(60, ChronoUnit.MINUTES), OrderEstado.PENDIENTE, null);

        ResponseEntity<byte[]> response = controller.exportOrdersByPickup(TODAY);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotEmpty();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Horario de Retiro");
            Row dataRow = sheet.getRow(1);
            assertThat(dataRow.getCell(1).getStringCellValue()).isEqualTo("Coty");
            assertThat(dataRow.getCell(2).getStringCellValue()).isEqualTo(dish.getNombre());
        }
    }

    @Test
    @DisplayName("exportOrdersByPickup(): sin pedidos para la fecha, rechaza con no-orders")
    void exportOrdersByPickupSinPedidosRechaza() {
        assertThatThrownBy(() -> controller.exportOrdersByPickup(TODAY.plusDays(5)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "no-orders");
    }

    // ─── Regresión: la vista y exportación existentes por empresa siguen intactas ──

    @Test
    @DisplayName("getOrders()/today: sigue devolviendo el consolidado por empresa igual que antes de esta unidad")
    void getOrdersPorEmpresaSinCambioDeComportamiento() {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section);
        Company company = persistCompany(category);
        User employee = persistEmployee(company, category);

        persistCompanyChoice(company, employee, dish, OrderEstado.CONFIRMADO);

        List<AdminOrderDto> today = controller.getOrders(TODAY);

        assertThat(today).hasSize(1);
        AdminOrderDto row = today.get(0);
        assertThat(row.companyId()).isEqualTo(company.getId());
        assertThat(row.companyName()).isEqualTo(company.getNombre());
        assertThat(row.dishNombre()).isEqualTo(dish.getNombre());
        assertThat(row.estado()).isEqualTo(OrderEstado.CONFIRMADO);
    }

    @Test
    @DisplayName("exportCompanyOrders()/deliver-company: side effects existentes (COMANDADO al exportar, ENTREGADO al marcar) sin cambios")
    void exportYDeliverPorEmpresaSinCambioDeComportamiento() throws Exception {
        Category category = persistCategory(1);
        MenuSection section = persistMenuSection();
        Dish dish = persistDish(category, section);
        Company company = persistCompany(category);
        User employee = persistEmployee(company, category);

        DailyChoice choice = persistCompanyChoice(company, employee, dish, OrderEstado.CONFIRMADO);

        ResponseEntity<byte[]> exported = controller.exportCompanyOrders(company.getId(), TODAY);
        assertThat(exported.getBody()).isNotEmpty();

        // markComandadoByCompany es un UPDATE en bloque (@Modifying): no
        // refresca el first-level cache — mismo gotcha documentado en
        // OrderPlacementServiceTest. Hace falta limpiar el contexto de
        // persistencia para releer el estado real.
        entityManager.clear();
        DailyChoice afterExport = dailyChoiceRepo.findById(choice.getId()).orElseThrow();
        assertThat(afterExport.getEstado()).isEqualTo(OrderEstado.COMANDADO);

        Map<String, Integer> delivered = controller.markDeliveredByCompany(company.getId());
        assertThat(delivered.get("updated")).isEqualTo(1);

        entityManager.clear();
        DailyChoice afterDeliver = dailyChoiceRepo.findById(choice.getId()).orElseThrow();
        assertThat(afterDeliver.getEstado()).isEqualTo(OrderEstado.ENTREGADO);
    }
}
