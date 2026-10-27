package com.arias.auth;

import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/v1/auth/check-email} end to end, unauthenticated: JSON shape of the response. */
@SpringBootTest
@Transactional
class CheckEmailControllerTest {

    private static final String URL = "/api/v1/auth/check-email";

    @Autowired private WebApplicationContext webContext;
    @Autowired private UserRepository userRepo;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    private static String body(String email) {
        return "{\"email\":\"" + email + "\"}";
    }

    @Test
    void unknownEmailReturnsNotFoundWithoutAuth() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                .content(body("nobody-" + System.nanoTime() + "@test.arias.com")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("NOT_FOUND"))
            .andExpect(jsonPath("$.requiresFirstLogin").value(false));
    }

    @Test
    void googleOnlyAccountReturnsGoogleStatus() throws Exception {
        String email = "ctrl-check-google-" + System.nanoTime() + "@test.arias.com";
        userRepo.save(User.builder().email(email).role(Role.EMPLOYEE).active(true)
            .googleSub("sub-" + System.nanoTime()).build());

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("GOOGLE"))
            .andExpect(jsonPath("$.requiresFirstLogin").value(false));
    }
}
