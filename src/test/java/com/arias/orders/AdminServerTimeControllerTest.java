package com.arias.orders;

import com.arias.auth.JwtService;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/v1/admin/server-time}: server clock for the kitchen board timers. */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(AdminServerTimeControllerTest.FixedClockConfig.class)
class AdminServerTimeControllerTest {

    static final Instant NOW = Instant.parse("2026-03-10T15:00:30Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(NOW, ZoneId.of("America/Argentina/Buenos_Aires"));
        }
    }

    @Autowired private WebApplicationContext webContext;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepo;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    private String bearer(Role role) {
        User u = userRepo.save(User.builder().email("t-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x").role(role).active(true).build());
        return "Bearer " + jwtService.issueAccessToken(u);
    }

    @Test
    @DisplayName("returns the server clock as an ISO-8601 instant with offset")
    void returnsServerNow() throws Exception {
        mvc.perform(get("/api/v1/admin/server-time").header(HttpHeaders.AUTHORIZATION, bearer(Role.SUPER_ADMIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.now").value("2026-03-10T12:00:30-03:00"));
    }

    @Test
    @DisplayName("is SUPER_ADMIN only")
    void requiresSuperAdmin() throws Exception {
        mvc.perform(get("/api/v1/admin/server-time").header(HttpHeaders.AUTHORIZATION, bearer(Role.EMPLOYEE)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/server-time")).andExpect(status().isUnauthorized());
    }
}
