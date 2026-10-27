package com.arias.users.notifications;

/**
 * Actualización parcial: solo se modifican los campos presentes (no null).
 * Al menos uno es obligatorio — lo valida el controller.
 */
public record UpdateNotificationPreferencesRequest(
    Boolean recibeRecordatorioPedido,
    Boolean recibeAvisoRetiro,
    Boolean recibeNovedades
) {}
