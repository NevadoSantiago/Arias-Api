package com.arias.companies;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.common.exception.BusinessException;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyService {

    private final CompanyRepository companyRepo;
    private final UserRepository userRepo;
    private final CategoryRepository categoryRepo;
    private final CompanyCategoryPriceRepository priceRepo;

    @Transactional(readOnly = true)
    public List<CompanyDto> listAll() {
        return companyRepo.findAll().stream()
            .sorted((a, b) -> a.getNombre().compareToIgnoreCase(b.getNombre()))
            .map(this::toDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public CompanyDto get(Long id) {
        return toDto(findCompany(id));
    }

    /**
     * Alta de empresa + creación del primer CompanyAdmin (whitelist).
     * El admin queda en estado "primer ingreso pendiente" (password_hash NULL).
     */
    @Transactional
    public CompanyDto create(CreateCompanyRequest req) {
        // CUIT único
        if (companyRepo.findByCuit(req.cuit()).isPresent()) {
            throw BusinessException.conflict("cuit-duplicado",
                "Ya existe una empresa con ese CUIT");
        }

        Category categoriaDefault = categoryRepo.findById(req.categoriaDefaultId())
            .orElseThrow(() -> BusinessException.notFound("category-not-found",
                "La categoría seleccionada no existe"));

        // Email del admin no puede colisionar con ningún user existente
        String adminEmail = req.adminEmail().trim().toLowerCase();
        if (userRepo.findByEmail(adminEmail).isPresent()) {
            throw BusinessException.conflict("email-duplicado",
                "Ya existe un usuario con ese email");
        }

        // Validar que vengan precios para TODAS las categorías existentes
        validateAllCategoriesPriced(req.categoryPrices());

        Company company = Company.builder()
            .nombre(req.nombre())
            .cuit(req.cuit())
            .calle(req.calle())
            .altura(req.altura())
            .piso(req.piso())
            .horaEntrega(req.horaEntrega())
            .categoriaDefault(categoriaDefault)
            .enabled(true)
            .build();
        companyRepo.save(company);

        // CompanyAdmin inicial — password NULL, se setea en first-login
        User admin = User.builder()
            .email(adminEmail)
            .role(Role.COMPANY_ADMIN)
            .company(company)
            .active(true)
            .build();
        userRepo.save(admin);

        // Persistir precios por categoría
        savePrices(company.getId(), req.categoryPrices());

        log.info("Empresa creada: {} (id={}) + admin {} (id={})",
            company.getNombre(), company.getId(), admin.getEmail(), admin.getId());

        return toDto(company);
    }

    @Transactional
    public CompanyDto update(Long id, UpdateCompanyRequest req) {
        Company company = findCompany(id);

        // Si cambió el CUIT, validar que no choque con otra empresa
        if (!company.getCuit().equals(req.cuit())) {
            companyRepo.findByCuit(req.cuit()).ifPresent(other -> {
                if (!other.getId().equals(id)) {
                    throw BusinessException.conflict("cuit-duplicado",
                        "Ya existe otra empresa con ese CUIT");
                }
            });
        }

        Category categoriaDefault = categoryRepo.findById(req.categoriaDefaultId())
            .orElseThrow(() -> BusinessException.notFound("category-not-found",
                "La categoría seleccionada no existe"));

        // Validar que vengan precios para todas las categorías existentes
        validateAllCategoriesPriced(req.categoryPrices());

        company.setNombre(req.nombre());
        company.setCuit(req.cuit());
        company.setCalle(req.calle());
        company.setAltura(req.altura());
        company.setPiso(req.piso());
        company.setHoraEntrega(req.horaEntrega());
        company.setCategoriaDefault(categoriaDefault);

        // Si vino adminEmail y cambió respecto al actual, actualizamos al admin
        if (req.adminEmail() != null && !req.adminEmail().isBlank()) {
            updateAdminEmail(company, req.adminEmail().trim().toLowerCase());
        }

        // Upsert de precios: borramos los previos y reinsertamos
        priceRepo.deleteByCompanyId(id);
        priceRepo.flush();
        savePrices(id, req.categoryPrices());

        return toDto(company);
    }

    /** Soft-disable. Conserva integridad referencial con users y orders. */
    @Transactional
    public void disable(Long id) {
        Company company = findCompany(id);
        company.setEnabled(false);
    }

    @Transactional
    public void enable(Long id) {
        Company company = findCompany(id);
        company.setEnabled(true);
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private Company findCompany(Long id) {
        return companyRepo.findById(id)
            .orElseThrow(() -> BusinessException.notFound("company-not-found",
                "Empresa no encontrada"));
    }

    /** Arma el DTO incluyendo el email del COMPANY_ADMIN principal y los precios. */
    private CompanyDto toDto(Company c) {
        String adminEmail = userRepo
            .findFirstByCompanyIdAndRoleOrderByIdAsc(c.getId(), Role.COMPANY_ADMIN)
            .map(User::getEmail)
            .orElse(null);
        Map<Long, Integer> prices = priceRepo.findByCompanyId(c.getId()).stream()
            .collect(Collectors.toMap(
                CompanyCategoryPrice::getCategoryId,
                CompanyCategoryPrice::getPrecio));
        return CompanyDto.from(c, adminEmail, prices);
    }

    /**
     * Verifica que el map de precios incluya TODAS las categorías existentes.
     * Si falta alguna o sobra una inexistente, error.
     */
    private void validateAllCategoriesPriced(Map<Long, Integer> prices) {
        Set<Long> existing = categoryRepo.findAll().stream()
            .map(Category::getId)
            .collect(Collectors.toSet());
        Set<Long> provided = prices == null ? Set.of() : prices.keySet();

        Set<Long> missing = new HashSet<>(existing);
        missing.removeAll(provided);
        if (!missing.isEmpty()) {
            throw BusinessException.badRequest("missing-category-prices",
                "Faltan precios para las categorías: " + missing);
        }

        Set<Long> extra = new HashSet<>(provided);
        extra.removeAll(existing);
        if (!extra.isEmpty()) {
            throw BusinessException.badRequest("invalid-category-prices",
                "Hay precios para categorías que no existen: " + extra);
        }

        for (Integer precio : prices.values()) {
            if (precio == null || precio < 0) {
                throw BusinessException.badRequest("invalid-precio",
                    "El precio debe ser un número mayor o igual a 0");
            }
        }
    }

    private void savePrices(Long companyId, Map<Long, Integer> prices) {
        for (var entry : prices.entrySet()) {
            priceRepo.save(CompanyCategoryPrice.builder()
                .companyId(companyId)
                .categoryId(entry.getKey())
                .precio(entry.getValue())
                .build());
        }
    }

    /**
     * Actualiza el email del COMPANY_ADMIN principal de la empresa.
     * Reglas:
     *  - Si el email no cambió, no hace nada
     *  - Si el nuevo email ya existe en OTRO user, error
     *  - Si la empresa no tiene admin, error
     */
    private void updateAdminEmail(Company company, String newEmail) {
        User admin = userRepo
            .findFirstByCompanyIdAndRoleOrderByIdAsc(company.getId(), Role.COMPANY_ADMIN)
            .orElseThrow(() -> BusinessException.notFound("admin-not-found",
                "Esta empresa no tiene un administrador. Contactá al equipo técnico."));

        if (admin.getEmail().equals(newEmail)) {
            return; // mismo email, no-op
        }

        // Validar que el email nuevo no esté tomado por OTRO user
        Optional<User> conflict = userRepo.findByEmail(newEmail);
        if (conflict.isPresent() && !conflict.get().getId().equals(admin.getId())) {
            throw BusinessException.conflict("email-duplicado",
                "Ya existe otro usuario con ese email");
        }

        String oldEmail = admin.getEmail();
        admin.setEmail(newEmail);
        log.info("Email del CompanyAdmin actualizado: {} → {} (user id={})",
            oldEmail, newEmail, admin.getId());
    }
}
