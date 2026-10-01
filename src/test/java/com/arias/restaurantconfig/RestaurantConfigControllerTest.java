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
import java.util.ArrayList;
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

    @Autowired
    private PickupScheduleRepository pickupScheduleRepo;

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
    @DisplayName("update(): sin dailySummaryTime en el request conserva el horario ya guardado")
    void updateSinDailySummaryTimeConservaElValorGuardado() {
        controller.update(validRequest()); // deja 07:45 guardado

        UpdateRestaurantConfigRequest withoutSummary = new UpdateRestaurantConfigRequest(
            LocalTime.of(9, 30), 30, 60,
            LocalTime.of(12, 0), LocalTime.of(16, 0),
            20, null, 15
        );
        RestaurantConfigDto updated = controller.update(withoutSummary);

        assertThat(updated.dailySummaryTime()).isEqualTo(LocalTime.of(7, 45));
        assertThat(repo.getSingleton().getDailySummaryTime()).isEqualTo(LocalTime.of(7, 45));
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

    // ─── Franja de retiro por día de la semana (migración V24, B5/F14) ────

    @Test
    @DisplayName("get(): incluye pickupSchedule con los 7 días sembrados por V24, ordenados lunes a domingo")
    void getIncluyePickupScheduleSembradoOrdenadoDeLunesADomingo() {
        RestaurantConfigDto dto = controller.get();

        assertThat(dto.pickupSchedule()).hasSize(7);
        assertThat(dto.pickupSchedule().stream().map(PickupScheduleDayDto::dayOfWeek).toList())
            .containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(dto.pickupSchedule()).allSatisfy(d -> assertThat(d.open()).isTrue());
    }

    private List<PickupScheduleDayRequest> validScheduleWith(int dayOfWeek, boolean open, LocalTime start, LocalTime end) {
        List<PickupScheduleDayRequest> days = new ArrayList<>();
        for (int dow = 1; dow <= 7; dow++) {
            if (dow == dayOfWeek) {
                days.add(new PickupScheduleDayRequest(dow, open, start, end));
            } else {
                days.add(new PickupScheduleDayRequest(dow, true, LocalTime.of(11, 0), LocalTime.of(15, 0)));
            }
        }
        return days;
    }

    @Test
    @DisplayName("updatePickupSchedule(): guarda los 7 días y el cambio se ve en get() (round-trip)")
    void updatePickupScheduleGuardaYSeVeEnGet() {
        List<PickupScheduleDayRequest> req = validScheduleWith(6, true, LocalTime.of(11, 0), LocalTime.of(16, 0));

        List<PickupScheduleDayDto> saved = controller.updatePickupSchedule(req);

        assertThat(saved).hasSize(7);
        PickupScheduleDayDto saturday = saved.stream().filter(d -> d.dayOfWeek() == 6).findFirst().orElseThrow();
        assertThat(saturday.open()).isTrue();
        assertThat(saturday.windowStart()).isEqualTo(LocalTime.of(11, 0));
        assertThat(saturday.windowEnd()).isEqualTo(LocalTime.of(16, 0));

        RestaurantConfigDto read = controller.get();
        PickupScheduleDayDto saturdayRead = read.pickupSchedule().stream()
            .filter(d -> d.dayOfWeek() == 6).findFirst().orElseThrow();
        assertThat(saturdayRead.windowEnd()).isEqualTo(LocalTime.of(16, 0));
    }

    @Test
    @DisplayName("updatePickupSchedule(): un día cerrado se guarda sin horarios")
    void updatePickupScheduleDiaCerradoSinHorarios() {
        List<PickupScheduleDayRequest> req = validScheduleWith(7, false, null, null);

        List<PickupScheduleDayDto> saved = controller.updatePickupSchedule(req);

        PickupScheduleDayDto sunday = saved.stream().filter(d -> d.dayOfWeek() == 7).findFirst().orElseThrow();
        assertThat(sunday.open()).isFalse();
        assertThat(sunday.windowStart()).isNull();
        assertThat(sunday.windowEnd()).isNull();
    }

    @Test
    @DisplayName("updatePickupSchedule(): rechaza cuando faltan días (6 en vez de 7), sin persistir nada")
    void updatePickupScheduleRechazaListaIncompleta() {
        List<PickupScheduleDayRequest> req = validScheduleWith(6, true, LocalTime.of(11, 0), LocalTime.of(16, 0));
        req.removeLast();

        assertThatThrownBy(() -> controller.updatePickupSchedule(req))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "invalid-pickup-schedule");
    }

    @Test
    @DisplayName("updatePickupSchedule(): rechaza con 400 un elemento null en la lista, no con 500")
    void updatePickupScheduleRechazaElementoNull() {
        List<PickupScheduleDayRequest> req = validScheduleWith(6, true, LocalTime.of(11, 0), LocalTime.of(16, 0));
        req.set(3, null);

        assertThatThrownBy(() -> controller.updatePickupSchedule(req))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "invalid-pickup-schedule");
    }

    @Test
    @DisplayName("updatePickupSchedule(): rechaza un día duplicado")
    void updatePickupScheduleRechazaDiaDuplicado() {
        List<PickupScheduleDayRequest> req = validScheduleWith(6, true, LocalTime.of(11, 0), LocalTime.of(16, 0));
        req.set(6, req.get(0)); // domingo (índice 6) duplica el lunes (índice 0)

        assertThatThrownBy(() -> controller.updatePickupSchedule(req))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "invalid-pickup-schedule");
    }

    @Test
    @DisplayName("updatePickupSchedule(): rechaza un día abierto cuyo cierre no es posterior a la apertura")
    void updatePickupScheduleRechazaCierreNoPosteriorAApertura() {
        List<PickupScheduleDayRequest> req = validScheduleWith(6, true, LocalTime.of(16, 0), LocalTime.of(16, 0));

        assertThatThrownBy(() -> controller.updatePickupSchedule(req))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "invalid-pickup-schedule");
    }

    @Test
    @DisplayName("updatePickupSchedule(): rechaza un día abierto sin horarios")
    void updatePickupScheduleRechazaAbiertoSinHorarios() {
        List<PickupScheduleDayRequest> req = validScheduleWith(6, true, null, null);

        assertThatThrownBy(() -> controller.updatePickupSchedule(req))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "invalid-pickup-schedule");
    }
}
