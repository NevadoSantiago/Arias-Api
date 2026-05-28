package com.arias.common.bootstrap;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.Dish;
import com.arias.catalog.dishes.DishRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.catalog.sides.Side;
import com.arias.catalog.sides.SideRepository;
import com.arias.catalog.sides.SideType;
import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * Carga demo data al arrancar la app en profile {@code dev}.
 *
 * <p>Idempotente: si ya hay companies, no hace nada.
 * Crea:
 * <ul>
 *   <li>1 Company de prueba ("Tech Corp SA")</li>
 *   <li>1 EMPLOYEE de prueba ({@code juan@tech.com} / {@code empleado123})</li>
 *   <li>18 platos con sus dish_side associations</li>
 * </ul>
 *
 * <p>Run order = 10 → corre DESPUÉS de AdminBootstrap (que no tiene @Order, default 0).
 */
@Component
@Profile("dev")
@Order(10)
@RequiredArgsConstructor
@Slf4j
public class DemoDataBootstrap implements CommandLineRunner {

    private final CompanyRepository companyRepo;
    private final UserRepository userRepo;
    private final CategoryRepository categoryRepo;
    private final MenuSectionRepository menuSectionRepo;
    private final SideRepository sideRepo;
    private final DishRepository dishRepo;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        if (companyRepo.count() > 0) {
            log.debug("Demo data ya existe (companies > 0) — skip");
            return;
        }

        log.warn("Cargando DEMO DATA para profile=dev…");

        // ─── Resolvemos referencias ───────────────────────────────────────
        Category premium = categoryRepo.findByNombre("Premium").orElseThrow();
        Category basico = categoryRepo.findByNombre("Básico").orElseThrow();

        MenuSection carnes    = menuSectionRepo.findByNombre("Carnes").orElseThrow();
        MenuSection minutas   = menuSectionRepo.findByNombre("Minutas").orElseThrow();
        MenuSection pastas    = menuSectionRepo.findByNombre("Pastas").orElseThrow();
        MenuSection sandwich  = menuSectionRepo.findByNombre("Sandwich").orElseThrow();
        MenuSection ensaladas = menuSectionRepo.findByNombre("Ensaladas").orElseThrow();

        Set<Side> guarniciones = Set.copyOf(sideRepo.findAllByTipoAndEnabledTrueOrderByNombreAsc(SideType.GUARNICION));
        Set<Side> salsas       = Set.copyOf(sideRepo.findAllByTipoAndEnabledTrueOrderByNombreAsc(SideType.SALSA));

        // ─── Company de prueba ─────────────────────────────────────────────
        Company techCorp = Company.builder()
            .nombre("Tech Corp SA")
            .cuit("30-12345678-9")
            .calle("Av. Corrientes")
            .altura("1500")
            .piso("5")
            .horaEntrega(LocalTime.of(12, 30))
            .categoriaDefault(basico)
            .enabled(true)
            .build();
        companyRepo.save(techCorp);

        // ─── Employee de prueba (categoría Premium → ve Premium + Básico) ─
        User juan = User.builder()
            .email("juan@tech.com")
            .passwordHash(passwordEncoder.encode("empleado123"))
            .firstName("Juan")
            .lastName("Pérez")
            .role(Role.EMPLOYEE)
            .company(techCorp)
            .category(premium)
            .active(true)
            .build();
        userRepo.save(juan);

        // ─── CompanyAdmin de prueba (admin de la empresa Tech Corp) ────────
        User techAdmin = User.builder()
            .email("admin@tech.com")
            .passwordHash(passwordEncoder.encode("tech123"))
            .firstName("Pedro")
            .lastName("Gerente")
            .role(Role.COMPANY_ADMIN)
            .company(techCorp)
            .active(true)
            .build();
        userRepo.save(techAdmin);

        // ─── 18 platos (replican el mock del frontend) ────────────────────
        List<Dish> dishes = List.of(
            // Carnes (Premium)
            dish("Tira de Asado", "Tira de asado a la parrilla con corte tradicional, cocción a punto.",
                "/dishes/tira-de-asado.jpg", premium, carnes, SideType.GUARNICION, guarniciones, 5, 5),
            dish("Ojo de Bife", "Corte premium 350g, jugoso, acompañado de manteca de hierbas.",
                "/dishes/ojo-de-bife.jpg", premium, carnes, SideType.GUARNICION, guarniciones, 3, 3),
            dish("Bife de Chorizo", "Bife de chorizo 350g a la parrilla, con su jugo.",
                "/dishes/bife-de-chorizo.jpg", premium, carnes, SideType.GUARNICION, guarniciones, 6, 6),
            dish("Vacío a la Parrilla", "Corte tradicional argentino, jugoso, terminado en la parrilla.",
                "/dishes/vacio.jpg", premium, carnes, SideType.GUARNICION, guarniciones, 4, 4),

            // Minutas (Básico)
            dish("Milanesa Napolitana", "Bife de nalga apanado, salsa de tomate, jamón, queso y orégano.",
                "/dishes/milanesa-napolitana.jpg", basico, minutas, SideType.GUARNICION, guarniciones, 8, 8),
            dish("Pollo al Disco", "Pollo desmenuzado cocinado al disco con verduras de estación.",
                "/dishes/pollo-al-disco.jpg", basico, minutas, SideType.GUARNICION, guarniciones, 8, 0),
            dish("Suprema Maryland", "Suprema apanada con panqueque, banana, ananá y crema de choclo.",
                "/dishes/suprema-maryland.jpg", basico, minutas, SideType.GUARNICION, guarniciones, 7, 7),
            dish("Tortilla de Papa y Cebolla", "Tortilla española tradicional, jugosa, sin acompañamiento.",
                "/dishes/tortilla.jpg", basico, minutas, null, Set.of(), 9, 9),

            // Pastas
            dish("Sorrentinos de Jamón y Queso", "Pasta rellena artesanal con jamón cocido y queso muzzarella.",
                "/dishes/sorrentinos.jpg", basico, pastas, SideType.SALSA, salsas, 12, 12),
            dish("Ñoquis de Papa", "Ñoquis caseros de papa, masa suave, salsa a elección.",
                "/dishes/noquis.jpg", basico, pastas, SideType.SALSA, salsas, 10, 10),
            dish("Tallarines Caseros", "Tallarines hechos a mano, al dente, salsa a elección.",
                "/dishes/tallarines.jpg", basico, pastas, SideType.SALSA, salsas, 8, 8),
            dish("Ravioles de Ricota y Espinaca", "Pasta rellena de ricota fresca y espinaca de hoja, salsa a elección.",
                "/dishes/ravioles.jpg", premium, pastas, SideType.SALSA, salsas, 8, 2),

            // Sandwich
            dish("Sandwich de Milanesa Completo", "Pan casero, milanesa, lechuga, tomate, huevo, jamón y queso.",
                "/dishes/sandwich-milanesa.jpg", basico, sandwich, SideType.GUARNICION, guarniciones, 6, 6),
            dish("Lomito Completo", "Lomito de cuadril, lechuga, tomate, jamón, queso y huevo en pan árabe.",
                "/dishes/lomito.jpg", premium, sandwich, SideType.GUARNICION, guarniciones, 5, 5),
            dish("Choripán Clásico", "Chorizo a la parrilla en pan crocante, chimichurri casero.",
                "/dishes/choripan.jpg", basico, sandwich, null, Set.of(), 8, 8),

            // Ensaladas (sin side)
            dish("Ensalada César", "Lechuga romana, pollo grillado, crutones, parmesano, aderezo césar.",
                "/dishes/cesar.jpg", basico, ensaladas, null, Set.of(), 7, 7),
            dish("Ensalada Caprese", "Tomates, muzzarella fresca, albahaca, aceite de oliva y reducción de balsámico.",
                "/dishes/caprese.jpg", basico, ensaladas, null, Set.of(), 5, 5),
            dish("Ensalada Mixta", "Lechuga, tomate, zanahoria, huevo duro, atún y aceitunas.",
                "/dishes/ensalada-mixta.jpg", basico, ensaladas, null, Set.of(), 10, 10)
        );
        dishRepo.saveAll(dishes);

        log.warn("╔════════════════════════════════════════════════════════════╗");
        log.warn("║  DEMO DATA cargado:                                         ║");
        log.warn("║    · Company: Tech Corp SA                                  ║");
        log.warn("║    · COMPANY_ADMIN: admin@tech.com / tech123                ║");
        log.warn("║    · EMPLOYEE: juan@tech.com / empleado123                  ║");
        log.warn("║    · 18 platos con sus sides asociados                      ║");
        log.warn("╚════════════════════════════════════════════════════════════╝");
    }

    /** Helper para crear platos con sintaxis compacta. */
    private static Dish dish(
        String nombre, String descripcion, String fotoUrl,
        Category category, MenuSection menuSection,
        SideType sideType, Set<Side> allowedSides,
        int stockDiarioDefault, int stockActual
    ) {
        return Dish.builder()
            .nombre(nombre)
            .descripcion(descripcion)
            .fotoUrl(fotoUrl)
            .category(category)
            .menuSection(menuSection)
            .sideType(sideType)
            .allowedSides(new java.util.HashSet<>(allowedSides))
            .enabled(true)
            .stockDiarioDefault(stockDiarioDefault)
            .stockActual(stockActual)
            .build();
    }
}
