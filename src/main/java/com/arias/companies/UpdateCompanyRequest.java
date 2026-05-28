package com.arias.companies;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

public record UpdateCompanyRequest(
    @NotBlank @Size(max = 150) String nombre,
    @NotBlank @Pattern(regexp = "\\d{2}-\\d{8}-\\d", message = "CUIT inválido (formato XX-XXXXXXXX-X)")
    String cuit,
    @NotBlank @Size(max = 200) String calle,
    @NotBlank @Size(max = 20) String altura,
    @Size(max = 20) String piso,
    @NotNull LocalTime horaEntrega,
    @NotNull Long categoriaDefaultId,

    /** Email del COMPANY_ADMIN. Si se manda y cambió respecto al actual,
     *  se actualiza el email del admin (un solo user). Opcional. */
    @Email @Size(max = 255) String adminEmail
) {}
