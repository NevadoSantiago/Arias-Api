package com.arias.users;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.dishes.DishRepository;
import com.arias.companies.Company;
import com.arias.companies.CompanyRepository;
import com.arias.common.exception.BusinessException;
import com.arias.orders.DailyChoice;
import com.arias.orders.DailyChoiceRepository;
import com.arias.orders.OrderEstado;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gestión de empleados desde el panel del CompanyAdmin.
 *
 * <p>Todos los métodos requieren el {@code companyId} del admin autenticado.
 * Ningún método trabaja "global" — siempre filtran/validan por la empresa
 * del JWT para evitar IDOR (un admin de una empresa accediendo a empleados
 * de otra).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyAdminEmployeeService {

    private final UserRepository userRepo;
    private final CompanyRepository companyRepo;
    private final CategoryRepository categoryRepo;
    private final DailyChoiceRepository orderRepo;
    private final DishRepository dishRepo;
    private final Clock clock;
    /** Para ejecutar cada email del bulk en su propia tx — si uno falla, los demás siguen. */
    private final TransactionTemplate txTemplate;

    /** Validación de email mínima — el formato real ya está validado por @Email en los requests. */
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    @Transactional(readOnly = true)
    public List<EmployeeDto> listEmployees(Long companyId) {
        return userRepo.findAllByCompanyIdAndRoleOrderByEmailAsc(companyId, Role.EMPLOYEE).stream()
            .map(EmployeeDto::from)
            .toList();
    }

    /**
     * Alta de empleado en la whitelist de la empresa.
     * El user se crea SIN password — debe completar el flujo de first-login.
     * Hereda automáticamente la {@code categoria_default} de la empresa.
     */
    @Transactional
    public EmployeeDto create(Long companyId, CreateEmployeeRequest req) {
        Company company = companyRepo.findById(companyId)
            .orElseThrow(() -> BusinessException.notFound("company-not-found", "Empresa no encontrada"));

        if (!Boolean.TRUE.equals(company.getEnabled())) {
            throw BusinessException.forbidden("company-disabled",
                "Tu empresa está desactivada. Contactá al SUPER_ADMIN.");
        }

        String email = req.email().trim().toLowerCase();
        if (userRepo.findByEmail(email).isPresent()) {
            throw BusinessException.conflict("email-duplicado",
                "Ya existe un usuario con ese email");
        }

        User employee = User.builder()
            .email(email)
            .role(Role.EMPLOYEE)
            .company(company)
            .category(company.getCategoriaDefault()) // hereda el tier de la empresa
            .active(true)
            .build();
        userRepo.save(employee);

        log.info("Empleado creado por CompanyAdmin: {} en empresa {} (id={})",
            email, company.getNombre(), employee.getId());

        return EmployeeDto.from(employee);
    }

    /**
     * Cambia la categoría de un empleado (por ejemplo un gerente que ve Premium
     * aunque la empresa default sea Básico).
     */
    @Transactional
    public EmployeeDto updateCategory(Long companyId, Long employeeId, Long categoryId) {
        User user = findOwn(companyId, employeeId);
        Category category = categoryRepo.findById(categoryId)
            .orElseThrow(() -> BusinessException.notFound("category-not-found",
                "Categoría no encontrada"));
        user.setCategory(category);
        log.info("Categoría de empleado {} cambiada a {} (id={})",
            user.getEmail(), category.getNombre(), categoryId);
        return EmployeeDto.from(user);
    }

    /**
     * Alta masiva — para el botón de "pegar muchos emails de una".
     * <p>NO marca @Transactional para que cada email sea una tx independiente
     * (vía {@link TransactionTemplate}). Si un INSERT falla (duplicate key,
     * constraint, etc.), solo abortamos esa tx — los demás emails siguen.
     */
    public BulkCreateEmployeesResult bulkCreate(Long companyId, BulkCreateEmployeesRequest req) {
        // Lookup inicial: extraemos los IDs primitivos + nombre para logs.
        // No retenemos el entity (queda detached al cerrar la tx).
        record CompanyRefs(Long id, Long categoryDefaultId, String nombre) {}
        CompanyRefs refs = txTemplate.execute(status -> {
            Company c = companyRepo.findById(companyId)
                .orElseThrow(() -> BusinessException.notFound("company-not-found", "Empresa no encontrada"));
            if (!Boolean.TRUE.equals(c.getEnabled())) {
                throw BusinessException.forbidden("company-disabled",
                    "Tu empresa está desactivada. Contactá al SUPER_ADMIN.");
            }
            return new CompanyRefs(c.getId(), c.getCategoriaDefault().getId(), c.getNombre());
        });

        List<EmployeeDto> created = new ArrayList<>();
        List<BulkCreateEmployeesResult.SkippedEmail> skipped = new ArrayList<>();
        Set<String> seenInThisBatch = new HashSet<>();

        for (String rawEmail : req.emails()) {
            String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
            if (email.isEmpty()) {
                continue;
            }
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                skipped.add(new BulkCreateEmployeesResult.SkippedEmail(rawEmail, "Email con formato inválido"));
                continue;
            }
            if (seenInThisBatch.contains(email)) {
                skipped.add(new BulkCreateEmployeesResult.SkippedEmail(email, "Repetido en la lista"));
                continue;
            }
            seenInThisBatch.add(email);

            // Cada email en SU PROPIA tx — fetcheamos company + category fresh.
            try {
                EmployeeDto dto = txTemplate.execute(status -> {
                    if (userRepo.findByEmail(email).isPresent()) {
                        throw new EmailAlreadyExistsException();
                    }
                    Company freshCompany = companyRepo.findById(refs.id()).orElseThrow();
                    Category freshCategory = categoryRepo.findById(refs.categoryDefaultId()).orElseThrow();
                    User employee = User.builder()
                        .email(email)
                        .role(Role.EMPLOYEE)
                        .company(freshCompany)
                        .category(freshCategory)
                        .active(true)
                        .build();
                    userRepo.save(employee);
                    return EmployeeDto.from(employee);
                });
                created.add(dto);
            } catch (EmailAlreadyExistsException e) {
                skipped.add(new BulkCreateEmployeesResult.SkippedEmail(email, "Ya existe un usuario con ese email"));
            } catch (Exception e) {
                log.error("Error creando {} en bulk: {}", email, e.getMessage());
                skipped.add(new BulkCreateEmployeesResult.SkippedEmail(email, "Error inesperado"));
            }
        }

        log.info("Bulk-create en empresa {}: {} creados, {} omitidos",
            refs.nombre(), created.size(), skipped.size());

        return new BulkCreateEmployeesResult(created, skipped);
    }

    /** Marker interna — escapamos del lambda con esto y lo capturamos arriba. */
    private static class EmailAlreadyExistsException extends RuntimeException {}

    @Transactional
    public void disable(Long companyId, Long employeeId) {
        User user = findOwn(companyId, employeeId);
        user.setActive(false);

        LocalDate today = LocalDate.now(clock);

        orderRepo.findByUserIdAndFecha(employeeId, today).ifPresent(order -> {
            if (order.getEstado() == OrderEstado.PENDIENTE) {
                dishRepo.incrementStock(order.getDish().getId());
                orderRepo.delete(order);
            }
        });

        List<DailyChoice> future = orderRepo.findByUserIdAndFechaBetweenOrderByFechaAsc(
            employeeId, today.plusDays(1), today.plusYears(1));
        for (DailyChoice o : future) {
            if (o.getEstado() == OrderEstado.PENDIENTE) {
                orderRepo.delete(o);
            }
        }
    }

    @Transactional
    public void enable(Long companyId, Long employeeId) {
        User user = findOwn(companyId, employeeId);
        user.setActive(true);
    }

    /**
     * Soft-delete del empleado. Setea {@code deletedAt = now} y desaparece
     * de listings, login, validaciones. Los pedidos históricos (DailyChoice)
     * siguen referenciándolo pero con los snapshots intactos — la cocina
     * sigue viendo el nombre del que pidió.
     *
     * <p>Solo permitimos archivar empleados ya INACTIVOS — fuerza el 2-step
     * "primero desactivar, después borrar". Reduce errores accidentales.
     */
    @Transactional
    public void archive(Long companyId, Long employeeId) {
        User user = findOwn(companyId, employeeId);
        if (Boolean.TRUE.equals(user.getActive())) {
            throw BusinessException.badRequest("must-disable-first",
                "Primero desactivá al empleado, después podés borrarlo");
        }
        user.setDeletedAt(java.time.Instant.now());
        log.info("Empleado borrado (soft): {} (id={})", user.getEmail(), user.getId());
    }

    /**
     * Busca el user verificando que pertenece a la empresa del CompanyAdmin
     * y que es un EMPLOYEE (no otro CompanyAdmin ni SUPER_ADMIN).
     *
     * <p>Defensa contra IDOR: si el id apunta a un user de otra empresa o de
     * otro role, respondemos 404 (no 403) para no revelar existencia.
     */
    private User findOwn(Long companyId, Long employeeId) {
        User user = userRepo.findById(employeeId)
            .orElseThrow(() -> BusinessException.notFound("employee-not-found",
                "Empleado no encontrado"));

        if (user.getRole() != Role.EMPLOYEE
            || user.getCompany() == null
            || !user.getCompany().getId().equals(companyId)) {
            throw BusinessException.notFound("employee-not-found", "Empleado no encontrado");
        }

        return user;
    }
}
