package com.arias.catalog.desserts;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin CRUD and customer listing of desserts, through the real security chain. */
@SpringBootTest
@Transactional
class DessertEndpointTest {

    private static final String ADMIN = "/api/v1/admin/desserts";
    private static final String AVAILABLE = "/api/v1/desserts/available";

    @Autowired private WebApplicationContext webContext;
    @Autowired private UserRepository userRepo;
    @Autowired private JwtService jwtService;
    @Autowired private DessertRepository dessertRepo;
    @Autowired private jakarta.persistence.EntityManager em;

    private MockMvc mockMvc;
    private String adminAuth;
    private String customerAuth;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        adminAuth = bearer(newUser(Role.SUPER_ADMIN));
        customerAuth = bearer(newUser(Role.EMPLOYEE));
    }

    private User newUser(Role role) {
        return userRepo.save(User.builder()
            .email("dessert-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x")
            .role(role)
            .active(true)
            .build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String auth, String body) {
        return b.header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long create(String nombre, int stock) throws Exception {
        String body = "{\"nombre\":\"" + nombre + "\",\"descripcion\":\"d\",\"stockDiarioDefault\":" + stock + "}";
        String res = mockMvc.perform(json(post(ADMIN), adminAuth, body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(res, "$.id")).longValue();
    }

    @Test
    @DisplayName("create trims the name and starts with today's stock equal to the daily default")
    void createSetsStock() throws Exception {
        mockMvc.perform(json(post(ADMIN), adminAuth,
                "{\"nombre\":\"  Flan casero  \",\"descripcion\":\"con dulce\",\"fotoUrl\":\"dishes/x.png\",\"stockDiarioDefault\":12}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nombre").value("Flan casero"))
            .andExpect(jsonPath("$.descripcion").value("con dulce"))
            .andExpect(jsonPath("$.fotoUrl").value("dishes/x.png"))
            .andExpect(jsonPath("$.stockDiarioDefault").value(12))
            .andExpect(jsonPath("$.stockActual").value(12))
            .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @DisplayName("create rejects a blank name, a negative stock and a missing stock with 400")
    void createValidation() throws Exception {
        mockMvc.perform(json(post(ADMIN), adminAuth, "{\"nombre\":\"   \",\"stockDiarioDefault\":1}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(json(post(ADMIN), adminAuth, "{\"nombre\":\"Flan\",\"stockDiarioDefault\":-1}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(json(post(ADMIN), adminAuth, "{\"nombre\":\"Flan\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("update changes the fields, honors enabled and an explicit stockActual")
    void update() throws Exception {
        long id = create("Flan", 5);

        mockMvc.perform(json(put(ADMIN + "/" + id), adminAuth,
                "{\"nombre\":\"Mousse\",\"descripcion\":null,\"stockDiarioDefault\":9,\"stockActual\":3,\"enabled\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nombre").value("Mousse"))
            .andExpect(jsonPath("$.stockDiarioDefault").value(9))
            .andExpect(jsonPath("$.stockActual").value(3))
            .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("update keeps today's stock when stockActual is omitted")
    void updateKeepsStock() throws Exception {
        long id = create("Flan", 5);
        dessertRepo.flush();
        dessertRepo.decrementStock(id);
        em.clear();

        mockMvc.perform(json(put(ADMIN + "/" + id), adminAuth,
                "{\"nombre\":\"Flan\",\"stockDiarioDefault\":8,\"enabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stockActual").value(4));
    }

    @Test
    @DisplayName("update of an unknown id is 404 dessert-not-found")
    void updateUnknown() throws Exception {
        mockMvc.perform(json(put(ADMIN + "/999999999"), adminAuth,
                "{\"nombre\":\"x\",\"stockDiarioDefault\":1,\"enabled\":true}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("dessert-not-found"));
    }

    @Test
    @DisplayName("DELETE disables, PUT enable re-enables; admin list keeps disabled ones")
    void disableEnable() throws Exception {
        long id = create("Flan", 5);

        mockMvc.perform(delete(ADMIN + "/" + id).header(HttpHeaders.AUTHORIZATION, adminAuth))
            .andExpect(status().isNoContent());
        mockMvc.perform(get(ADMIN).header(HttpHeaders.AUTHORIZATION, adminAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id==" + id + ")].enabled").value(hasItem(false)));

        mockMvc.perform(put(ADMIN + "/" + id + "/enable").header(HttpHeaders.AUTHORIZATION, adminAuth))
            .andExpect(status().isNoContent());
        mockMvc.perform(get(ADMIN).header(HttpHeaders.AUTHORIZATION, adminAuth))
            .andExpect(jsonPath("$[?(@.id==" + id + ")].enabled").value(hasItem(true)));
    }

    @Test
    @DisplayName("admin list excludes soft-deleted desserts")
    void adminListExcludesDeleted() throws Exception {
        long id = create("Borrado", 1);
        Dessert d = dessertRepo.findById(id).orElseThrow();
        d.setDeletedAt(Instant.now());
        dessertRepo.flush();

        mockMvc.perform(get(ADMIN).header(HttpHeaders.AUTHORIZATION, adminAuth))
            .andExpect(jsonPath("$[*].id").value(not(hasItem((int) id))));
    }

    @Test
    @DisplayName("customer listing shows enabled desserts (even at zero stock), hides disabled and deleted")
    void availableListing() throws Exception {
        long on = create("Visible", 0);
        long off = create("Oculto", 3);
        long gone = create("Borrado", 3);
        mockMvc.perform(delete(ADMIN + "/" + off).header(HttpHeaders.AUTHORIZATION, adminAuth));
        Dessert g = dessertRepo.findById(gone).orElseThrow();
        g.setDeletedAt(Instant.now());
        dessertRepo.flush();

        mockMvc.perform(get(AVAILABLE).header(HttpHeaders.AUTHORIZATION, customerAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id").value(hasItem((int) on)))
            .andExpect(jsonPath("$[*].id").value(not(hasItem((int) off))))
            .andExpect(jsonPath("$[*].id").value(not(hasItem((int) gone))))
            .andExpect(jsonPath("$[?(@.id==" + on + ")].stockActual").value(hasItem(0)))
            .andExpect(jsonPath("$[?(@.id==" + on + ")].stockDiarioDefault").isEmpty());
    }

    @Test
    @DisplayName("non-admins get 403 on admin endpoints; anonymous gets 401")
    void authorization() throws Exception {
        mockMvc.perform(get(ADMIN).header(HttpHeaders.AUTHORIZATION, customerAuth))
            .andExpect(status().isForbidden());
        mockMvc.perform(json(post(ADMIN), customerAuth, "{\"nombre\":\"x\",\"stockDiarioDefault\":1}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete(ADMIN + "/1").header(HttpHeaders.AUTHORIZATION, customerAuth))
            .andExpect(status().isForbidden());
        mockMvc.perform(get(AVAILABLE)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("edit and disable never write stock_actual, even over a stale loaded entity")
    void adminEditsLeaveStockUntouched() throws Exception {
        long id = create("Budin", 5);
        em.flush();
        em.clear();
        dessertRepo.findById(id).orElseThrow(); // stale copy (stock 5) stays in the persistence context
        dessertRepo.decrementStock(id);         // a concurrent order took one: real stock is 4

        mockMvc.perform(json(put(ADMIN + "/" + id), adminAuth,
                "{\"nombre\":\"Budin 2\",\"stockDiarioDefault\":9,\"enabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stockActual").value(4))
            .andExpect(jsonPath("$.nombre").value("Budin 2"));
        mockMvc.perform(delete(ADMIN + "/" + id).header(HttpHeaders.AUTHORIZATION, adminAuth))
            .andExpect(status().isNoContent());

        em.flush();
        em.clear();
        Dessert d = dessertRepo.findById(id).orElseThrow();
        assertThat(d.getStockActual()).isEqualTo(4);
        assertThat(d.getEnabled()).isFalse();
        assertThat(d.getStockDiarioDefault()).isEqualTo(9);
    }
}
