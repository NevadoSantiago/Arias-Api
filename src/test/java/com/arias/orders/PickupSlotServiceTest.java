package com.arias.orders;

import com.arias.common.exception.BusinessException;
import com.arias.restaurantconfig.FechaDeshabilitada;
import com.arias.restaurantconfig.FechaDeshabilitadaRepository;
import com.arias.restaurantconfig.PickupSchedule;
import com.arias.restaurantconfig.PickupScheduleRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unidad 8 — {@link PickupSlotService}: spec {@code pickup-scheduling}
 * completa. {@code now} fijo en 2026-03-10 10:50 ART (martes), con los
 * valores por defecto de {@code restaurant_config} sembrados por V20 (lead 20
 * min, ventana 11:00-15:00) y el paso de V23 (10 min).
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Transactional
@Import(PickupSlotServiceTest.FixedClockConfig.class)
class PickupSlotServiceTest {

    // 2026-03-10T13:50:00Z = 10:50 ART (UTC-3) — martes. lead=20 => earliest=11:10 ART.
    static final Instant FIXED_NOW = Instant.parse("2026-03-10T13:50:00Z");
    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZONE);
        }
    }

    @Autowired
    private PickupSlotService pickupSlotService;

    @Autowired
    private FechaDeshabilitadaRepository fechaDeshabilitadaRepo;

    @Autowired
    private PickupScheduleRepository pickupScheduleRepo;

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private UserRepository userRepo;

    private static LocalDate today() {
        return LocalDate.ofInstant(FIXED_NOW, ZONE);
    }

    private User persistUser() {
        User user = User.builder()
            .email("pickup-" + System.nanoTime() + "@test.arias.com")
            .role(Role.EMPLOYEE)
            .active(true)
            .build();
        return userRepo.save(user);
    }

    // ─── slotsFor(): ventana + paso + tiempo de preparación ────────────────

    @Test
    @DisplayName("slotsFor(): genera slots cada pickup_slot_minutes dentro de la ventana, filtrando los previos al lead")
    void slotsForRespetaVentanaPasoYLead() {
        List<Instant> slots = pickupSlotService.slotsFor(today());

        assertThat(slots).isNotEmpty();
        // earliest = 10:50 + 20min = 11:10 ART -> paso 10 (V23): el slot de
        // las 11:00 queda excluido, el de las 11:10 (== earliest) entra.
        assertThat(slots.get(0).atZone(ZONE).toLocalTime()).isEqualTo(LocalTime.of(11, 10));
        assertThat(slots.get(slots.size() - 1).atZone(ZONE).toLocalTime()).isEqualTo(LocalTime.of(14, 50));
        assertThat(slots).allSatisfy(s -> {
            LocalTime t = s.atZone(ZONE).toLocalTime();
            assertThat(t).isAfterOrEqualTo(LocalTime.of(11, 10));
            assertThat(t).isBefore(LocalTime.of(15, 0));
        });
    }

    @Test
    @DisplayName("slotsFor(): fecha fuera de la ventana de semana actual/siguiente devuelve lista vacía")
    void slotsForFechaFueraDeSemanaDevuelveVacio() {
        List<Instant> slots = pickupSlotService.slotsFor(today().plusDays(15));
        assertThat(slots).isEmpty();
    }

    @Test
    @DisplayName("slotsFor(): fecha deshabilitada devuelve lista vacía aunque esté dentro de la ventana de semanas")
    void slotsForFechaDeshabilitadaDevuelveVacio() {
        LocalDate manana = today().plusDays(1);
        fechaDeshabilitadaRepo.save(FechaDeshabilitada.builder().fecha(manana).motivo("Feriado de prueba").build());

        List<Instant> slots = pickupSlotService.slotsFor(manana);

        assertThat(slots).isEmpty();
    }

    @Test
    @DisplayName("assertValidPickupTime(): rechaza una fecha deshabilitada aunque el horario sea válido")
    void assertValidPickupTimeRechazaFechaDeshabilitada() {
        LocalDate manana = today().plusDays(1);
        fechaDeshabilitadaRepo.save(FechaDeshabilitada.builder().fecha(manana).motivo("Feriado de prueba").build());
        Instant pickupAt = pickupSlotService.slotsFor(today()).getFirst()
            .atZone(ZONE).plusDays(1).toInstant();

        assertThatThrownBy(() -> pickupSlotService.assertValidPickupTime(pickupAt))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no recibe pedidos");
    }

    @Test
    @DisplayName("slotsFor(): sin límite de capacidad — varios pedidos en el mismo horario no lo quitan de la lista")
    void slotsForSinLimiteDeCapacidad() {
        List<Instant> before = pickupSlotService.slotsFor(today());
        Instant sameSlot = before.get(0);

        for (int i = 0; i < 3; i++) {
            User user = persistUser();
            orderRepo.save(Order.builder()
                .user(user)
                .fecha(today())
                .pickupAt(sameSlot)
                .estado(OrderEstado.PENDIENTE)
                .creditTotal(1)
                .build());
        }

        List<Instant> after = pickupSlotService.slotsFor(today());

        assertThat(after).isEqualTo(before);
        assertThat(after).contains(sameSlot);
    }

    // ─── assertValidPickupTime(): cada motivo de rechazo + bordes ──────────

    @Test
    @DisplayName("assertValidPickupTime(): acepta un horario válido dentro de la semana, ventana y lead")
    void assertValidPickupTimeAceptaHorarioValido() {
        Instant valido = today().plusDays(1).atTime(12, 0).atZone(ZONE).toInstant();
        assertThatCode(() -> pickupSlotService.assertValidPickupTime(valido)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertValidPickupTime(): acepta exactamente el borde de apertura de la ventana")
    void assertValidPickupTimeAceptaBordeInicioVentana() {
        Instant borde = today().plusDays(1).atTime(11, 0).atZone(ZONE).toInstant();
        assertThatCode(() -> pickupSlotService.assertValidPickupTime(borde)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertValidPickupTime(): acepta exactamente el borde del lead (now + lead)")
    void assertValidPickupTimeAceptaBordeLead() {
        Instant borde = FIXED_NOW.plus(20, ChronoUnit.MINUTES); // 11:10 ART, dentro de la ventana
        assertThatCode(() -> pickupSlotService.assertValidPickupTime(borde)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertValidPickupTime(): rechaza un horario antes de la apertura de la ventana")
    void assertValidPickupTimeRechazaAntesDeLaVentana() {
        Instant antesDeVentana = today().plusDays(1).atTime(10, 59).atZone(ZONE).toInstant();
        assertThatThrownBy(() -> pickupSlotService.assertValidPickupTime(antesDeVentana))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "pickup-outside-service-window");
    }

    @Test
    @DisplayName("assertValidPickupTime(): rechaza un horario en o después del cierre de la ventana")
    void assertValidPickupTimeRechazaEnElCierreDeLaVentana() {
        Instant cierre = today().plusDays(1).atTime(15, 0).atZone(ZONE).toInstant();
        assertThatThrownBy(() -> pickupSlotService.assertValidPickupTime(cierre))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "pickup-outside-service-window");
    }

    @Test
    @DisplayName("assertValidPickupTime(): rechaza una fecha posterior a la semana siguiente")
    void assertValidPickupTimeRechazaFueraDeSemana() {
        Instant fueraDeRango = today().plusDays(15).atTime(12, 0).atZone(ZONE).toInstant();
        assertThatThrownBy(() -> pickupSlotService.assertValidPickupTime(fueraDeRango))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "pickup-out-of-range");
    }

    @Test
    @DisplayName("assertValidPickupTime(): rechaza un horario dentro de la ventana pero demasiado próximo (antes de now + lead)")
    void assertValidPickupTimeRechazaDemasiadoProximo() {
        // 11:00 ART hoy (alineado al paso de 10 min): dentro de la ventana
        // (11:00-15:00) pero antes del earliest (10:50 + 20min = 11:10).
        Instant demasiadoProximo = today().atTime(11, 0).atZone(ZONE).toInstant();
        assertThatThrownBy(() -> pickupSlotService.assertValidPickupTime(demasiadoProximo))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "pickup-too-soon");
    }

    // ─── assertValidPickupTime(): alineación al paso configurado (V23) ─────

    @Test
    @DisplayName("assertValidPickupTime(): acepta un horario alineado al paso contado desde el inicio de la ventana")
    void assertValidPickupTimeAceptaHorarioAlineadoAlPaso() {
        // Ventana desde las 11:00, paso 10 -> 12:10 está alineado (70 min, múltiplo de 10).
        Instant alineado = today().plusDays(1).atTime(12, 10).atZone(ZONE).toInstant();
        assertThatCode(() -> pickupSlotService.assertValidPickupTime(alineado)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertValidPickupTime(): rechaza un horario no alineado al paso configurado")
    void assertValidPickupTimeRechazaHorarioNoAlineadoAlPaso() {
        // 12:07 -> 67 min desde las 11:00, no es múltiplo de 10.
        Instant noAlineado = today().plusDays(1).atTime(12, 7).atZone(ZONE).toInstant();
        assertThatThrownBy(() -> pickupSlotService.assertValidPickupTime(noAlineado))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "pickup-time-not-aligned");
    }

    @Test
    @DisplayName("assertValidPickupTime(): rechaza un horario con segundos distintos de cero aunque el minuto esté alineado")
    void assertValidPickupTimeRechazaSegundosDistintosDeCero() {
        Instant conSegundos = today().plusDays(1).atTime(12, 10, 30).atZone(ZONE).toInstant();
        assertThatThrownBy(() -> pickupSlotService.assertValidPickupTime(conSegundos))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "pickup-time-not-aligned");
    }

    // ─── Franja por día de la semana (B5/F14, migración V24) ───────────────

    /** Sábado dentro de la semana actual (lunes 2026-03-09..domingo 2026-03-15). */
    private static LocalDate saturdayThisWeek() {
        return today().plusDays(4); // martes + 4 = sábado
    }

    /** Domingo dentro de la semana actual. */
    private static LocalDate sundayThisWeek() {
        return today().plusDays(5); // martes + 5 = domingo
    }

    private void setSchedule(int dayOfWeek, boolean open, LocalTime start, LocalTime end) {
        PickupSchedule schedule = pickupScheduleRepo.getByDayOfWeek(dayOfWeek);
        schedule.setOpen(open);
        schedule.setWindowStart(start);
        schedule.setWindowEnd(end);
        pickupScheduleRepo.save(schedule);
    }

    @Test
    @DisplayName("slotsFor(): usa la ventana propia del día de la semana (sábado 11:00-16:00, resto de la semana 11:00-23:00)")
    void slotsForUsaLaVentanaDelDiaDeLaSemana() {
        for (int dow = 1; dow <= 5; dow++) {
            setSchedule(dow, true, LocalTime.of(11, 0), LocalTime.of(23, 0));
        }
        setSchedule(6, true, LocalTime.of(11, 0), LocalTime.of(16, 0)); // sábado

        LocalDate sabado = saturdayThisWeek();
        List<Instant> slotsSabado = pickupSlotService.slotsFor(sabado);
        List<Instant> slotsMartes = pickupSlotService.slotsFor(today());

        assertThat(slotsSabado).isNotEmpty();
        assertThat(slotsSabado.get(0).atZone(ZONE).toLocalTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(slotsSabado.getLast().atZone(ZONE).toLocalTime()).isEqualTo(LocalTime.of(15, 50));
        assertThat(slotsSabado).allSatisfy(s -> assertThat(s.atZone(ZONE).toLocalTime()).isBefore(LocalTime.of(16, 0)));

        // El martes (día de semana) sigue teniendo su propia ventana, hasta 22:50.
        assertThat(slotsMartes.getLast().atZone(ZONE).toLocalTime()).isEqualTo(LocalTime.of(22, 50));
    }

    @Test
    @DisplayName("slotsFor(): un día cerrado no ofrece ningún horario")
    void slotsForDiaCerradoDevuelveVacio() {
        setSchedule(7, false, null, null); // domingo cerrado

        List<Instant> slots = pickupSlotService.slotsFor(sundayThisWeek());

        assertThat(slots).isEmpty();
    }

    @Test
    @DisplayName("assertValidPickupTime(): rechaza un día cerrado con un mensaje de negocio claro")
    void assertValidPickupTimeRechazaDiaCerrado() {
        setSchedule(7, false, null, null); // domingo cerrado
        Instant pickupAt = sundayThisWeek().atTime(12, 0).atZone(ZONE).toInstant();

        assertThatThrownBy(() -> pickupSlotService.assertValidPickupTime(pickupAt))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "pickup-day-closed")
            .hasMessage("El local no abre ese día.");
    }

    @Test
    @DisplayName("assertValidPickupTime(): la alineación al paso se cuenta desde el inicio de la ventana propia del día, no de otro día")
    void assertValidPickupTimeAlineaAlInicioDeLaVentanaDelDiaCorrespondiente() {
        // Sábado abre a las 11:05 (distinto del resto de la semana, que abre a las 11:00).
        setSchedule(6, true, LocalTime.of(11, 5), LocalTime.of(16, 0));
        LocalDate sabado = saturdayThisWeek();

        // 12:05 -> 60 min desde 11:05 (múltiplo de 10): alineado si se cuenta desde
        // el inicio del sábado. Si se contara mal desde 11:00 (65 min) se rechazaría.
        Instant alineadoAlSabado = sabado.atTime(12, 5).atZone(ZONE).toInstant();
        assertThatCode(() -> pickupSlotService.assertValidPickupTime(alineadoAlSabado)).doesNotThrowAnyException();

        // 12:00 -> 55 min desde 11:05: NO es múltiplo de 10, debe rechazarse.
        Instant noAlineadoAlSabado = sabado.atTime(12, 0).atZone(ZONE).toInstant();
        assertThatThrownBy(() -> pickupSlotService.assertValidPickupTime(noAlineadoAlSabado))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", "pickup-time-not-aligned");
    }
}
