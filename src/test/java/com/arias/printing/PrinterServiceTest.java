package com.arias.printing;

import com.arias.catalog.dishes.DishStation;
import com.arias.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Printer host/port validation. No Spring, no network. */
class PrinterServiceTest {

    private PrinterRepository repo;
    private PrinterService service;

    @BeforeEach
    void setUp() {
        repo = mock(PrinterRepository.class);
        service = new PrinterService(repo);
        when(repo.findById(DishStation.COCINA))
            .thenReturn(Optional.of(Printer.builder().station(DishStation.COCINA).host("192.168.0.121").port(9100).build()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.0.100", "192.168.255.254", "10.0.0.1", "10.255.255.255",
        "172.16.0.1", "172.31.255.255"})
    @DisplayName("private IPv4 literals are accepted")
    void acceptsPrivateIpv4(String host) {
        Printer p = service.update(DishStation.COCINA, host, 9100);

        assertThat(p.getHost()).isEqualTo(host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "11.0.0.1",
        "127.0.0.1", "169.254.1.1", "0.0.0.0", "192.168.0", "192.168.0.256", "192.168.0.1.5",
        "192.168.00.1", "192.168.0.01", "printer.local", "localhost", "", "  ", "192.168.0.1:9100",
        "::1", "fe80::1", "0xC0.0xA8.0.1", "-1.168.0.1", "192.168.0.-1"})
    @DisplayName("anything that is not a private IPv4 literal is rejected")
    void rejectsNonPrivate(String host) {
        assertThatThrownBy(() -> service.update(DishStation.COCINA, host, 9100))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("null host is rejected")
    void rejectsNullHost() {
        assertThatThrownBy(() -> service.update(DishStation.COCINA, null, 9100))
            .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, 100000})
    @DisplayName("ports outside 1-65535 are rejected")
    void rejectsBadPort(int port) {
        assertThatThrownBy(() -> service.update(DishStation.COCINA, "192.168.0.121", port))
            .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 9100, 65535})
    @DisplayName("ports within 1-65535 are accepted")
    void acceptsGoodPort(int port) {
        assertThat(service.update(DishStation.COCINA, "192.168.0.121", port).getPort()).isEqualTo(port);
    }

    @Test
    @DisplayName("host is trimmed before it is validated and stored")
    void trimsHost() {
        assertThat(service.update(DishStation.COCINA, " 192.168.0.5 ", 9100).getHost()).isEqualTo("192.168.0.5");
    }
}
