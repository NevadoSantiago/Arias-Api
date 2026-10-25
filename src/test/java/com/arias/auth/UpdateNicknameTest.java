package com.arias.auth;

import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code PATCH /api/v1/auth/me}: the logged-in user edits their own alias. */
@SpringBootTest
@Transactional
class UpdateNicknameTest {

    private static final String URL = "/api/v1/auth/me";

    @Autowired private WebApplicationContext webContext;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepo;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    private User user(Role role, String nickname, String phone) {
        return userRepo.save(User.builder()
            .email("nick-" + System.nanoTime() + "@test.arias.com").passwordHash("x")
            .role(role).active(true).firstName("Ana").lastName("Perez")
            .nickname(nickname).phone(phone).build());
    }

    private String bearer(User u) {
        return "Bearer " + jwtService.issueAccessToken(u);
    }

    private static String body(String nickname) {
        return "{\"nickname\":" + (nickname == null ? "null" : "\"" + nickname + "\"") + "}";
    }

    @Test
    @DisplayName("updates the nickname, trims it and recomputes displayName")
    void updatesNickname() throws Exception {
        User u = user(Role.EMPLOYEE, null, null);

        mvc.perform(patch(URL).header(HttpHeaders.AUTHORIZATION, bearer(u))
                .contentType(MediaType.APPLICATION_JSON).content(body("  Coty  ")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nickname").value("Coty"))
            .andExpect(jsonPath("$.displayName").value("Coty"))
            .andExpect(jsonPath("$.email").value(u.getEmail()));

        assertThat(userRepo.findById(u.getId()).orElseThrow().getNickname()).isEqualTo("Coty");
    }

    @Test
    @DisplayName("works for every role")
    void worksForEveryRole() throws Exception {
        for (Role role : Role.values()) {
            User u = user(role, "Viejo", null);
            mvc.perform(patch(URL).header(HttpHeaders.AUTHORIZATION, bearer(u))
                    .contentType(MediaType.APPLICATION_JSON).content(body("Nuevo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Nuevo"));
        }
    }

    @Test
    @DisplayName("rejects blank, too short, too long and null nicknames with 400")
    void rejectsInvalidNicknames() throws Exception {
        User u = user(Role.EMPLOYEE, "Coty", null);
        for (String invalid : new String[] {"", "   ", "a", " a ", "x".repeat(51), null}) {
            mvc.perform(patch(URL).header(HttpHeaders.AUTHORIZATION, bearer(u))
                    .contentType(MediaType.APPLICATION_JSON).content(body(invalid)))
                .andExpect(status().isBadRequest());
        }
        assertThat(userRepo.findById(u.getId()).orElseThrow().getNickname()).isEqualTo("Coty");
    }

    @Test
    @DisplayName("accepts the 2 and 50 character boundaries")
    void acceptsBoundaries() throws Exception {
        User u = user(Role.EMPLOYEE, null, null);
        mvc.perform(patch(URL).header(HttpHeaders.AUTHORIZATION, bearer(u))
                .contentType(MediaType.APPLICATION_JSON).content(body("ab")))
            .andExpect(status().isOk());
        mvc.perform(patch(URL).header(HttpHeaders.AUTHORIZATION, bearer(u))
                .contentType(MediaType.APPLICATION_JSON).content(body("x".repeat(50))))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("requires authentication (401)")
    void requiresAuthentication() throws Exception {
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content(body("Coty")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("does not touch phone or other fields")
    void doesNotTouchOtherFields() throws Exception {
        User u = user(Role.EMPLOYEE, "Viejo", "+5491159876547");

        mvc.perform(patch(URL).header(HttpHeaders.AUTHORIZATION, bearer(u))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"Nuevo\",\"phone\":\"1100000000\",\"firstName\":\"Otro\"}"))
            .andExpect(status().isOk());

        User after = userRepo.findById(u.getId()).orElseThrow();
        assertThat(after.getPhone()).isEqualTo("+5491159876547");
        assertThat(after.getFirstName()).isEqualTo("Ana");
        assertThat(after.getNickname()).isEqualTo("Nuevo");
    }
}
