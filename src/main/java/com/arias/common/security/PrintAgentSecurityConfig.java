package com.arias.common.security;

import com.arias.printing.PrintAgentKeyFilter;
import com.arias.printing.PrintAgentKeyVerifier;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Separate chain for the local print agent: {@code /api/v1/print-agent/**} only, stateless, no JWT,
 * authenticated by the {@code X-Print-Agent-Key} header. {@code @Order(1)} makes it win over the
 * JWT chain for that path; every other path keeps going through {@link SecurityConfig}.
 */
@Configuration
public class PrintAgentSecurityConfig {

    public static final String PATH = "/api/v1/print-agent/**";

    @Bean
    @Order(1)
    public SecurityFilterChain printAgentFilterChain(HttpSecurity http, PrintAgentKeyVerifier verifier)
        throws Exception {
        http
            .securityMatcher(PATH)
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint((req, res, ex) -> res.setStatus(HttpServletResponse.SC_UNAUTHORIZED))
            )
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(PrintAgentKeyFilter.ROLE))
            .addFilterBefore(new PrintAgentKeyFilter(verifier), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
