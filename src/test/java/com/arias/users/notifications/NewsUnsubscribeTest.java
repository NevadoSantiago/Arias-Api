package com.arias.users.notifications;

import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public unsubscribe link of the news (marketing) emails, with a token distinct from the reminder one. */
@SpringBootTest
@Transactional
class NewsUnsubscribeTest {

    private static final String URL = "/api/v1/me/unsubscribe-news";

    @Autowired private WebApplicationContext webContext;
    @Autowired private UserRepository userRepo;
    @Autowired private NewsUnsubscribeToken newsTokens;
    @Autowired private ReminderUnsubscribeToken reminderTokens;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    private User newUser() {
        return userRepo.save(User.builder()
            .email("news-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x")
            .role(Role.EMPLOYEE)
            .active(true)
            .build());
    }

    @Test
    @DisplayName("users receive news by default")
    void defaultsToTrue() {
        assertThat(newUser().getRecibeNovedades()).isTrue();
    }

    @Test
    @DisplayName("token round-trips the user id")
    void tokenRoundTrip() {
        assertThat(newsTokens.verify(newsTokens.generate(42L))).contains(42L);
    }

    @Test
    @DisplayName("a reminder token cannot unsubscribe news and vice versa")
    void tokensAreNotInterchangeable() {
        assertThat(newsTokens.verify(reminderTokens.generate(42L))).isEmpty();
        assertThat(reminderTokens.verify(newsTokens.generate(42L))).isEmpty();
    }

    @Test
    @DisplayName("a tampered token is rejected")
    void tamperedTokenRejected() {
        String token = newsTokens.generate(42L);
        assertThat(newsTokens.verify(token + "x")).isEmpty();
        assertThat(newsTokens.verify("garbage")).isEmpty();
        assertThat(newsTokens.verify(null)).isEmpty();
    }

    @Test
    @DisplayName("POST with a valid token (no auth) turns recibeNovedades off and is idempotent")
    void unsubscribeIsPublicAndIdempotent() throws Exception {
        User user = newUser();
        String token = newsTokens.generate(user.getId());

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(URL).param("t", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()));
        }

        User saved = userRepo.findById(user.getId()).orElseThrow();
        assertThat(saved.getRecibeNovedades()).isFalse();
        assertThat(saved.getRecibeRecordatorioPedido()).isTrue();
        assertThat(saved.getRecibeAvisoRetiro()).isTrue();
    }

    @Test
    @DisplayName("POST with a reminder token or an invalid token is a 400 and changes nothing")
    void invalidTokenRejected() throws Exception {
        User user = newUser();

        mockMvc.perform(post(URL).param("t", reminderTokens.generate(user.getId())))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL).param("t", "nope"))
            .andExpect(status().isBadRequest());

        assertThat(userRepo.findById(user.getId()).orElseThrow().getRecibeNovedades()).isTrue();
    }
}
