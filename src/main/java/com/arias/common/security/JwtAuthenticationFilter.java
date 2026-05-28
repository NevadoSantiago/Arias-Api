package com.arias.common.security;

import com.arias.auth.JwtService;
import com.arias.users.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Filtro que lee el header {@code Authorization: Bearer <jwt>},
 * valida el token y popula el {@link SecurityContextHolder} con un {@link JwtUser}.
 *
 * <p>Si no hay token o es inválido, el filter pasa de largo — Spring Security
 * decidirá si el endpoint requiere autenticación o no.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain chain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());

        try {
            Claims claims = jwtService.parseAccessToken(token);
            Role role = jwtService.extractRole(claims);

            JwtUser principal = new JwtUser(
                Long.valueOf(claims.getSubject()),
                claims.get("email", String.class),
                role,
                // Los números JSON pueden deserializarse como Integer; convertir a Long manualmente
                asLong(claims.get("companyId")),
                asLong(claims.get("categoryId"))
            );

            var authority = new SimpleGrantedAuthority("ROLE_" + role.name());
            var authToken = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(authority)
            );
            SecurityContextHolder.getContext().setAuthentication(authToken);
        } catch (JwtException ignored) {
            // Token inválido/expirado → no autenticamos. Spring Security responde 401 si el endpoint lo requiere.
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }

    /** Convierte un Number (puede venir como Integer, Long, etc.) a Long. */
    private static Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        return null;
    }
}
