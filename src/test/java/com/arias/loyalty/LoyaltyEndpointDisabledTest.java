package com.arias.loyalty;

import com.arias.auth.JwtService;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static com.arias.loyalty.LoyaltyTestSupport.newUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The endpoint reports the feature as disabled while the switch is off. */
@SpringBootTest(properties = "arias.loyalty.enabled=false")
@Transactional
class LoyaltyEndpointDisabledTest {

    @Autowired private WebApplicationContext webContext;
    @Autowired private UserRepository userRepo;
    @Autowired private JwtService jwtService;

    @Test
    @DisplayName("GET /api/v1/loyalty answers enabled=false and nothing else")
    void disabled() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        User user = newUser(userRepo);

        mockMvc.perform(get("/api/v1/loyalty")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issueAccessToken(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.milestones").doesNotExist())
            .andExpect(jsonPath("$.cycle").doesNotExist());
    }
}
