package com.arias.common.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Configuración de Spring Security.
 *
 * <p>Modelo:
 * <ul>
 *   <li>Stateless (no HttpSession) — JWT en cada request</li>
 *   <li>CSRF deshabilitado — la app es API-first, no formularios server-rendered</li>
 *   <li>CORS habilitado para el frontend (Vite dev: localhost:5173, prod: cf pages)</li>
 *   <li>Endpoints de auth + actuator/health: PÚBLICOS</li>
 *   <li>Todo lo demás: requiere autenticación (JwtAuthenticationFilter popula el contexto)</li>
 * </ul>
 *
 * <p>{@code @EnableMethodSecurity} permite usar @PreAuthorize en controllers/services.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final List<String> allowedOrigins;

    public SecurityConfig(
        JwtAuthenticationFilter jwtFilter,
        @Value("${arias.cors.allowed-origins}") String allowedOriginsCsv
    ) {
        this.jwtFilter = jwtFilter;
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Si no hay autenticación válida → 401 (no 403). Importante: el frontend
            // refresca tokens automáticamente cuando recibe 401, pero NO cuando recibe 403.
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint((req, res, ex) -> res.setStatus(HttpServletResponse.SC_UNAUTHORIZED))
            )
            .authorizeHttpRequests(auth -> auth
                // Preflight CORS
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Auth endpoints públicos — incluye /logout porque solo necesita
                // la cookie de refresh (no el access token, que puede estar expirado)
                .requestMatchers(
                    "/api/v1/auth/check-email",
                    "/api/v1/auth/login",
                    "/api/v1/auth/first-login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    "/api/v1/auth/forgot-password",
                    "/api/v1/auth/reset-password"
                ).permitAll()
                // Health check
                .requestMatchers("/actuator/health/**").permitAll()
                // Todo lo demás autenticado
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // OJO: con allowCredentials=true NO se puede usar "*" en origins
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of(HttpHeaders.AUTHORIZATION));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt con cost 12 — balance security/perf para 2026
        return new BCryptPasswordEncoder(12);
    }
}
