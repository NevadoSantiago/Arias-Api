package com.arias.credits.discount;

import com.arias.auth.JwtService;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/v1/credits/wallet} exposes the usable account discount, or null. */
@SpringBootTest
@Transactional
class WalletDiscountEndpointTest {

    private static final String URL = "/api/v1/credits/wallet";

    @Autowired private WebApplicationContext webContext;
    @Autowired private UserRepository userRepo;
    @Autowired private JwtService jwtService;
    @Autowired private AccountDiscountService discountService;
    @Autowired private AccountDiscountRepository discountRepo;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    private User newUser() {
        return userRepo.save(User.builder()
            .email("wallet-disc-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x")
            .role(Role.EMPLOYEE)
            .active(true)
            .build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    @Test
    @DisplayName("discount is null when the user has none")
    void nullWithoutDiscount() throws Exception {
        User user = newUser();

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.discount").value((Object) null));
    }

    @Test
    @DisplayName("discount carries percent and expiresAt while usable")
    void presentWhenUsable() throws Exception {
        User user = newUser();
        discountService.grantIfAbsent(user, 10, "test");

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.discount.percent").value(10))
            .andExpect(jsonPath("$.discount.expiresAt").exists());
    }

    @Test
    @DisplayName("discount is null once expired or consumed")
    void nullWhenNotUsable() throws Exception {
        User user = newUser();
        discountService.grantIfAbsent(user, 10, "test");
        AccountDiscount d = discountRepo.findByUserId(user.getId()).orElseThrow();
        d.setExpiresAt(Instant.now().minusSeconds(60));
        discountRepo.saveAndFlush(d);

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(jsonPath("$.discount").value((Object) null));

        d.setExpiresAt(Instant.now().plusSeconds(3600));
        d.setConsumedAt(Instant.now());
        discountRepo.saveAndFlush(d);

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(jsonPath("$.discount").value((Object) null));
    }
}
