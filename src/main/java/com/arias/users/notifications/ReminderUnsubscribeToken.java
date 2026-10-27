package com.arias.users.notifications;

import com.arias.auth.JwtProperties;
import org.springframework.stereotype.Component;

/**
 * Token chico HMAC para el link "no quiero más recordatorios" del mail.
 *
 * <p>No expira — un mismo link sirve para siempre (no es una acción sensible,
 * a lo sumo desactiva sus propios mails). Firma sin prefijo de propósito para
 * que sigan valiendo los links ya enviados; el de novedades usa otro propósito
 * ({@link NewsUnsubscribeToken}) y no es intercambiable con éste.
 */
@Component
public class ReminderUnsubscribeToken extends UserHmacToken {

    public ReminderUnsubscribeToken(JwtProperties jwtProps) {
        super(jwtProps, "");
    }
}
