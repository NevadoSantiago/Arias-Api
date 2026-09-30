package com.arias.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * Bean del verificador de ID tokens de Google, separado de {@link
 * GoogleAuthService} a propósito: así {@code GoogleAuthServiceTest} puede
 * reemplazarlo con {@code @MockitoBean} sin pegarle a la red de Google ni
 * necesitar credenciales reales (unidad 5, harness de la tabla de unidades
 * de trabajo).
 *
 * <p>{@code GoogleIdTokenVerifier} ya valida emisor ({@code
 * accounts.google.com}/{@code https://accounts.google.com}) por default sin
 * necesidad de {@code setIssuers(...)} — diseño §Decisión 9.
 */
@Configuration
public class GoogleAuthConfig {

    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier(GoogleAuthProperties props) {
        return new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
            .setAudience(Collections.singletonList(props.clientId()))
            .build();
    }
}
