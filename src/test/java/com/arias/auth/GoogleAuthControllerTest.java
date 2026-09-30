package com.arias.auth;

import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.json.webtoken.JsonWebSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/auth/google} de punta a punta (filtros de seguridad,
 * controller y serialización de errores). {@link GoogleIdTokenVerifier}
 * queda mockeado — ver {@link GoogleAuthServiceTest} para el porqué de usar
 * instancias reales de {@code GoogleIdToken}.
 */
@SpringBootTest
@Transactional
class GoogleAuthControllerTest {

    private static final String URL = "/api/v1/auth/google";

    @Autowired private WebApplicationContext webContext;
    @Autowired private UserRepository userRepo;

    @MockitoBean
    private GoogleIdTokenVerifier verifier;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    private GoogleIdToken validToken(String email) {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("sub-" + System.nanoTime());
        payload.setEmail(email);
        payload.setEmailVerified(true);
        payload.set("given_name", "Ana");
        return new GoogleIdToken(new JsonWebSignature.Header(), payload, new byte[0], new byte[0]);
    }

    private static String body(String idToken) {
        return "{\"idToken\":\"" + idToken + "\"}";
    }

    @Test
    void googleConTokenValidoDeClienteB2cDevuelveAccessToken() throws Exception {
        String email = "ctrl-google-ok-" + System.nanoTime() + "@test.arias.com";
        when(verifier.verify("token-ok")).thenReturn(validToken(email));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("token-ok")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void googleConCuentaDeEmpresaDevuelve403ConCodigoEspecifico() throws Exception {
        String email = "ctrl-google-admin-" + System.nanoTime() + "@test.arias.com";
        userRepo.save(User.builder()
            .email(email)
            .firstName("Root")
            .role(Role.SUPER_ADMIN)
            .active(true)
            .emailVerifiedAt(Instant.now())
            .build());
        when(verifier.verify("token-admin")).thenReturn(validToken(email));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("token-admin")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.title").value("GOOGLE_ACCOUNT_NOT_ALLOWED"))
            .andExpect(jsonPath("$.detail").value("Tu cuenta es de empresa. Ingresá con tu email"))
            .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void googleConTokenInvalidoDevuelve401Generico() throws Exception {
        when(verifier.verify("token-malo")).thenReturn(null);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("token-malo")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.title").value("Email o contraseña incorrectos"))
            .andExpect(jsonPath("$.accessToken").doesNotExist());
    }
}
