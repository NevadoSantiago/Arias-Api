package com.arias.printing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates the print agent by the {@code X-Print-Agent-Key} header. Deliberately NOT a Spring
 * bean: it is built by the print-agent security chain only, so Boot does not also register it as a
 * global servlet filter.
 */
public class PrintAgentKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Print-Agent-Key";
    public static final String ROLE = "PRINT_AGENT";

    private final PrintAgentKeyVerifier verifier;

    public PrintAgentKeyFilter(PrintAgentKeyVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain chain
    ) throws ServletException, IOException {
        if (!verifier.verify(request.getHeader(HEADER))) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "print-agent", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
        chain.doFilter(request, response);
    }
}
