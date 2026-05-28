package com.arias.users;

/**
 * Roles del sistema. Se persiste como VARCHAR (string), no como ORDINAL,
 * para que el agregado/reordenado de roles no rompa la BD existente.
 */
public enum Role {
    SUPER_ADMIN,
    COMPANY_ADMIN,
    EMPLOYEE
}
