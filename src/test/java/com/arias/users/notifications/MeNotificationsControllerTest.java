package com.arias.users.notifications;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET/PUT /api/v1/me/notifications}: preferencias de mail, con actualización parcial. */
@SpringBootTest
@Transactional
class MeNotificationsControllerTest {

    private static final String URL = "/api/v1/me/notifications";

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
            .email("prefs-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x")
            .role(Role.EMPLOYEE)
            .active(true)
            .build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    @Test
    @DisplayName("GET returns both preferences, defaulting to true")
    void getReturnsBothPreferences() throws Exception {
        User user = newUser();

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recibeRecordatorioPedido").value(true))
            .andExpect(jsonPath("$.recibeAvisoRetiro").value(true));
    }

    @Test
    @DisplayName("PUT with only recibeAvisoRetiro updates it and leaves the other untouched")
    void putOnlyPickupNotice() throws Exception {
        User user = newUser();

        mockMvc.perform(put(URL).header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"recibeAvisoRetiro\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recibeAvisoRetiro").value(false))
            .andExpect(jsonPath("$.recibeRecordatorioPedido").value(true));

        User saved = userRepo.findById(user.getId()).orElseThrow();
        assertThat(saved.getRecibeAvisoRetiro()).isFalse();
        assertThat(saved.getRecibeRecordatorioPedido()).isTrue();
    }

    @Test
    @DisplayName("PUT with only recibeRecordatorioPedido keeps working (backwards compatible)")
    void putOnlyDailyReminder() throws Exception {
        User user = newUser();

        mockMvc.perform(put(URL).header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"recibeRecordatorioPedido\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recibeRecordatorioPedido").value(false))
            .andExpect(jsonPath("$.recibeAvisoRetiro").value(true));

        assertThat(userRepo.findById(user.getId()).orElseThrow().getRecibeAvisoRetiro()).isTrue();
    }

    @Test
    @DisplayName("PUT with neither field is rejected with 400")
    void putWithNoFieldsIsRejected() throws Exception {
        User user = newUser();

        mockMvc.perform(put(URL).header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET returns recibeNovedades, defaulting to true")
    void getReturnsNewsPreference() throws Exception {
        User user = newUser();

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recibeNovedades").value(true));
    }

    @Test
    @DisplayName("PUT with only recibeNovedades updates it and leaves the others untouched")
    void putOnlyNews() throws Exception {
        User user = newUser();

        mockMvc.perform(put(URL).header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"recibeNovedades\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recibeNovedades").value(false))
            .andExpect(jsonPath("$.recibeRecordatorioPedido").value(true))
            .andExpect(jsonPath("$.recibeAvisoRetiro").value(true));

        assertThat(userRepo.findById(user.getId()).orElseThrow().getRecibeNovedades()).isFalse();
    }

    @Test
    @DisplayName("PUT without recibeNovedades does not change it")
    void putWithoutNewsKeepsIt() throws Exception {
        User user = newUser();
        user.setRecibeNovedades(false);
        userRepo.saveAndFlush(user);

        mockMvc.perform(put(URL).header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"recibeAvisoRetiro\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recibeNovedades").value(false));

        assertThat(userRepo.findById(user.getId()).orElseThrow().getRecibeNovedades()).isFalse();
    }
}
