package com.arias.companies;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.Map;

/**
 * Alta de empresa + creación del primer CompanyAdmin (whitelist).
 *
 * <p>El CompanyAdmin se crea sin password — debe completar el flujo de
 * first-login para setearla.
 *
 * <p>{@code categoryPrices} mapea {@code categoryId → precio en pesos sin
 * decimales}. Debe incluir TODAS las categorías existentes.
 */
public record CreateCompanyRequest(
    @NotBlank @Size(max = 150) String nombre,
    @NotBlank @Pattern(regexp = "\\d{2}-\\d{8}-\\d", message = "CUIT inválido (formato XX-XXXXXXXX-X)")
    String cuit,
    @NotBlank @Size(max = 200) String calle,
    @NotBlank @Size(max = 20) String altura,
    @Size(max = 20) String piso,
    @NotNull LocalTime horaEntrega,
    @NotNull Long categoriaDefaultId,

    // Datos del CompanyAdmin inicial (se crea junto con la empresa)
    @NotBlank @Email @Size(max = 255) String adminEmail,

    /** Precios acordados por categoría. Required: una entry por cada categoría existente. */
    @NotNull Map<Long, Integer> categoryPrices
) {}
