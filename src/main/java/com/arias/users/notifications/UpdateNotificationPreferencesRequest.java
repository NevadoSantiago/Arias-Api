package com.arias.users.notifications;

import jakarta.validation.constraints.NotNull;

public record UpdateNotificationPreferencesRequest(
    @NotNull Boolean recibeRecordatorioPedido
) {}
