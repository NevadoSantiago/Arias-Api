package com.arias.users.notifications;

import com.arias.auth.JwtProperties;
import org.springframework.stereotype.Component;

/**
 * Token HMAC del link "No quiero recibir más novedades" de los mails de marketing.
 * Propósito distinto al del recordatorio: un token de uno nunca verifica en el otro.
 */
@Component
public class NewsUnsubscribeToken extends UserHmacToken {

    public NewsUnsubscribeToken(JwtProperties jwtProps) {
        super(jwtProps, "news:");
    }
}
