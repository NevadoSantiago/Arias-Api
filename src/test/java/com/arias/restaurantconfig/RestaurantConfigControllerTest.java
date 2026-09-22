package com.arias.restaurantconfig;

import com.arias.common.exception.BusinessException;
import com.arias.common.security.JwtUser;
import com.arias.users.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unidad 13 — {@link RestaurantConfigController#update}: confirma que los 7
 * campos de configuración B2C agregados en la unidad 8 (migración V20:
 * tiempo de preparación, vencimiento de créditos, ventana de retiro y sus
 * derivados) son editables por el {@code SUPER_ADMIN} — tarea que la unidad
 * 8 adelantó al exponer el endpoint; esta unidad solo agrega la cobertura de
 * test (ver nota en la unidad 8 y 13.3 de {@code tasks.md}).
 */
@SpringBootTest
@Transactional
class RestaurantConfigControllerTest {

    @Autowired
    private RestaurantConfigController controller;

    @Autowired
    private RestaurantConfigRepository repo;

    @BeforeEach
    void authenticateAsSuperAdmin() {
        JwtUser principal = new JwtUser(1L, "admin@arias.com", Role.SUPER_ADMIN, null, null);
        var authority = new SimpleGrantedAuthority("ROLE_SUPER_ADMIN");
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(authority)));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private UpdateRestaurantConfigRequest validRequest() {
        return new UpdateRestaurantConfigRequest(
            LocalTime.of(9, 30),   // horaCorte
            30,                    // pickupLeadMinutes
            60,                    // creditExpiryDays
            LocalTime.of(12, 0),   // pickupWindowStart
            LocalTime.of(16, 0),   // pickupWindowEnd
            20,                    // pickupSlotMinutes
            LocalTime.of(7, 45),   // dailySummaryTime
            15                     // pickupReminderMinutes
        );
    }

    @Test
    @DisplayName("update(): el SUPER_ADMIN edita los 7 campos B2C (lead, vencimiento, ventana, paso, resumen, recordatorio) y la hora de corte")
    void updateEditaLosSieteCamposB2cYLaHoraDeCorte() {
        RestaurantConfigDto updated = controller.update(validRequest());

        assertThat(updated.horaCorte()).isEqualTo(LocalTime.of(9, 30));
        assertThat(updated.pickupLeadMinutes()).isEqualTo(30);
        assertThat(updated.creditExpiryDays()).isEqualTo(60);
        assertThat(updated.pickupWindowStart()).isEqualTo(LocalTime.of(12, 0));
        assertThat(updated.pickupWindowEnd()).isEqualTo(LocalTime.of(16, 0));
        assertThat(updated.pickupSlotMinutes()).isEqualTo(20);
        assertThat(updated.dailySummaryTime()).isEqualTo(LocalTime.of(7, 45));
        assertThat(updated.pickupReminderMinutes()).isEqualTo(15);

        // Persistido de verdad, no solo devuelto en el DTO.
        RestaurantConfig persisted = repo.getSingleton();
        assertThat(persisted.getPickupWindowStart()).isEqualTo(LocalTime.of(12, 0));
        assertThat(persisted.getCreditExpiryDays()).isEqualTo(60);

        RestaurantConfigDto read = controller.get();
        assertThat(read.pickupLeadMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("update(): rechaza cuando la ventana de retiro abre después de (o igual a) cuando cierra, sin persistir nada")
    void updateRechazaVentanaInvalida() {
        LocalTime originalStart = repo.getSingleton().getPickupWindowStart();

        UpdateRestaurantConfigRequest invalid = new UpdateRestaurantConfigRequest(
            LocalTime.of(9, 30), 30, 60,
            LocalTime.of(16, 0),  // start
            LocalTime.of(16, 0),  // end == start → inválido (no es before)
            20, LocalTime.of(7, 45), 15
        );

        assertThatThrownBy(() -> controller.update(invalid))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "invalid-pickup-window");

        assertThat(repo.getSingleton().getPickupWindowStart()).isEqualTo(originalStart);
    }
}
