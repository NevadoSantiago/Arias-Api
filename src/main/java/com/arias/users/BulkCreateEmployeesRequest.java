package com.arias.users;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Alta en batch de empleados. Mandás muchos emails, el backend procesa cada uno
 * y devuelve un resumen con los que creó y los que omitió (junto con el motivo).
 *
 * <p>Limit de 200 por request para no saturar la BD y mantener latencia baja.
 */
public record BulkCreateEmployeesRequest(
    @NotEmpty(message = "Tenés que mandar al menos un email")
    @Size(max = 200, message = "Máximo 200 emails por request")
    List<String> emails
) {}
