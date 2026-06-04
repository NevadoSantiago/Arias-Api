package com.arias.contact;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de cotización enviada desde la landing pública.
 * Campos opcionales (telefono, empleados, mensaje) pueden venir null.
 */
public record QuoteRequest(
    @NotBlank @Size(max = 100) String nombre,
    @NotBlank @Size(max = 120) String empresa,
    @NotBlank @Email @Size(max = 150) String email,
    @Size(max = 40) String telefono,
    @Min(1) Integer empleados,
    @Size(max = 1000) String mensaje
) {}
