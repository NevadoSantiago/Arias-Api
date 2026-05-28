package com.arias.companies;

import java.time.LocalTime;

public record CompanyDto(
    Long id,
    String nombre,
    String cuit,
    String calle,
    String altura,
    String piso,
    LocalTime horaEntrega,
    Long categoriaDefaultId,
    String categoriaDefaultNombre,
    Boolean enabled,
    /** Email del COMPANY_ADMIN principal — null si por algún motivo no tiene admin. */
    String adminEmail
) {
    public static CompanyDto from(Company c, String adminEmail) {
        return new CompanyDto(
            c.getId(),
            c.getNombre(),
            c.getCuit(),
            c.getCalle(),
            c.getAltura(),
            c.getPiso(),
            c.getHoraEntrega(),
            c.getCategoriaDefault().getId(),
            c.getCategoriaDefault().getNombre(),
            c.getEnabled(),
            adminEmail
        );
    }
}
