package com.arias.companies;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/companies")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class CompanyController {

    private final CompanyService companyService;

    @GetMapping
    public List<CompanyDto> list() {
        return companyService.listAll();
    }

    @GetMapping("/{id}")
    public CompanyDto get(@PathVariable Long id) {
        return companyService.get(id);
    }

    @PostMapping
    public CompanyDto create(@Valid @RequestBody CreateCompanyRequest req) {
        return companyService.create(req);
    }

    @PutMapping("/{id}")
    public CompanyDto update(@PathVariable Long id, @Valid @RequestBody UpdateCompanyRequest req) {
        return companyService.update(id, req);
    }

    /** Soft-disable (enabled = false). Toggle a través de PUT /{id}/enable */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disable(@PathVariable Long id) {
        companyService.disable(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/enable")
    public ResponseEntity<Void> enable(@PathVariable Long id) {
        companyService.enable(id);
        return ResponseEntity.noContent().build();
    }
}
