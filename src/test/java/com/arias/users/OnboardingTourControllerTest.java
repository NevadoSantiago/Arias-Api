package com.arias.users;

import com.arias.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tour de primer ingreso: {@code onboardingTourSeenAt} en {@code /auth/me} y
 * {@code POST /api/v1/me/onboarding-tour/seen} (idempotente), de punta a punta.
 */
@SpringBootTest
@Transactional
class OnboardingTourControllerTest {

    private static final String SEEN_URL = "/api/v1/me/onboarding-tour/seen";

    @Autowired private WebApplicationContext webContext;
    @Autowired private UserRepository userRepo;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    private User newUser() {
        return userRepo.save(User.builder()
            .email("tour-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x")
            .role(Role.EMPLOYEE)
            .active(true)
            .build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    @Test
    @DisplayName("a new account starts with the tour unseen")
    void newAccountStartsWithNullTimestamp() {
        assertThat(newUser().getOnboardingTourSeenAt()).isNull();
    }

    @Test
    @DisplayName("GET /auth/me exposes onboardingTourSeenAt as null for an unseen tour")
    void meExposesNullWhenUnseen() throws Exception {
        User user = newUser();

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboardingTourSeenAt").value((Object) null));
    }

    @Test
    @DisplayName("POST seen stores the timestamp and /auth/me then returns it")
    void seenStoresTimestamp() throws Exception {
        User user = newUser();

        mockMvc.perform(post(SEEN_URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isNoContent());

        assertThat(userRepo.findById(user.getId()).orElseThrow().getOnboardingTourSeenAt()).isNotNull();
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(jsonPath("$.onboardingTourSeenAt").isNotEmpty());
    }

    @Test
    @DisplayName("POST seen is idempotent: a second call keeps the first timestamp")
    void seenIsIdempotent() throws Exception {
        User user = newUser();
        Instant first = Instant.parse("2020-01-01T00:00:00Z");
        user.setOnboardingTourSeenAt(first);
        userRepo.saveAndFlush(user);

        mockMvc.perform(post(SEEN_URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isNoContent());

        assertThat(userRepo.findById(user.getId()).orElseThrow().getOnboardingTourSeenAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("POST seen without a session is rejected with 401")
    void seenRequiresAuthentication() throws Exception {
        mockMvc.perform(post(SEEN_URL)).andExpect(status().isUnauthorized());
    }
}
