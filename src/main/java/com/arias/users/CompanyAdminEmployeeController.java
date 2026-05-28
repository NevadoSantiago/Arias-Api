package com.arias.users;

import com.arias.common.exception.BusinessException;
import com.arias.common.security.JwtUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints del panel del CompanyAdmin sobre sus empleados.
 * El companyId siempre se toma del JWT — el frontend NO puede pasarlo.
 */
@RestController
@RequestMapping("/api/v1/company-admin/employees")
@RequiredArgsConstructor
@PreAuthorize("hasRole('COMPANY_ADMIN')")
public class CompanyAdminEmployeeController {

    private final CompanyAdminEmployeeService service;

    @GetMapping
    public List<EmployeeDto> list(@AuthenticationPrincipal JwtUser user) {
        return service.listEmployees(ensureCompany(user));
    }

    @PostMapping
    public EmployeeDto create(
        @AuthenticationPrincipal JwtUser user,
        @Valid @RequestBody CreateEmployeeRequest req
    ) {
        return service.create(ensureCompany(user), req);
    }

    @PostMapping("/bulk")
    public BulkCreateEmployeesResult bulkCreate(
        @AuthenticationPrincipal JwtUser user,
        @Valid @RequestBody BulkCreateEmployeesRequest req
    ) {
        return service.bulkCreate(ensureCompany(user), req);
    }

    @PutMapping("/{id}/category")
    public EmployeeDto updateCategory(
        @AuthenticationPrincipal JwtUser user,
        @PathVariable Long id,
        @Valid @RequestBody UpdateEmployeeCategoryRequest req
    ) {
        return service.updateCategory(ensureCompany(user), id, req.categoryId());
    }

    /** Soft-delete: solo válido para empleados inactivos. */
    @DeleteMapping("/{id}/archive")
    public ResponseEntity<Void> archive(
        @AuthenticationPrincipal JwtUser user,
        @PathVariable Long id
    ) {
        service.archive(ensureCompany(user), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disable(
        @AuthenticationPrincipal JwtUser user,
        @PathVariable Long id
    ) {
        service.disable(ensureCompany(user), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/enable")
    public ResponseEntity<Void> enable(
        @AuthenticationPrincipal JwtUser user,
        @PathVariable Long id
    ) {
        service.enable(ensureCompany(user), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sanity check: el COMPANY_ADMIN tiene que tener companyId en el JWT.
     * Si por algún motivo no lo tiene (data inconsistente), no podemos resolver
     * "qué empresa es la suya" — error claro.
     */
    private Long ensureCompany(JwtUser user) {
        if (user.companyId() == null) {
            throw BusinessException.forbidden("no-company",
                "Tu usuario no tiene empresa asignada");
        }
        return user.companyId();
    }
}
