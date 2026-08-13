package com.arias.billing;

import com.arias.billing.BillingDtos.BillingPeriod;
import com.arias.billing.BillingDtos.CompanyBilling;
import com.arias.common.exception.BusinessException;
import com.arias.orders.DailyChoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests de la agregación de facturación. Sin Spring: la lógica que importa
 * (agrupar, sumar, detectar pedidos sin tarifa) es pura.
 */
class BillingServiceTest {

    private static final LocalDate DESDE = LocalDate.of(2026, 8, 3);  // lunes
    private static final LocalDate HASTA = LocalDate.of(2026, 8, 9);  // domingo

    private DailyChoiceRepository repo;
    private BillingService service;

    @BeforeEach
    void setUp() {
        repo = mock(DailyChoiceRepository.class);
        service = new BillingService(repo);
    }

    private void givenRows(BillingRow... rows) {
        when(repo.aggregateBilling(any(), any(), any())).thenReturn(List.of(rows));
    }

    private void givenDaily(DailyTotalRow... rows) {
        when(repo.aggregateDailyTotals(any(), any(), any())).thenReturn(List.of(rows));
    }

    private static DailyTotalRow dia(int diaDelMes, long pedidos, long total) {
        return new DailyTotalRow(LocalDate.of(2026, 8, diaDelMes), pedidos, total);
    }

    private static BillingRow row(long companyId, String company, String categoria,
                                  Integer precio, long cantidad) {
        return new BillingRow(companyId, company, 10L, categoria, categoria, precio, cantidad);
    }

    @Test
    @DisplayName("suma el subtotal por categoría y el total de la empresa")
    void sumaTotalesPorEmpresa() {
        givenRows(
            row(1L, "ACME", "Premium", 8000, 12),
            row(1L, "ACME", "Basico", 5000, 30)
        );

        BillingPeriod report = service.report(DESDE, HASTA, null);

        assertThat(report.empresas()).hasSize(1);
        CompanyBilling acme = report.empresas().getFirst();
        assertThat(acme.totalPedidos()).isEqualTo(42);
        assertThat(acme.total()).isEqualTo(12 * 8000L + 30 * 5000L);
        assertThat(report.totalGeneral()).isEqualTo(acme.total());
    }

    @Test
    @DisplayName("agrupa varias empresas por separado")
    void separaEmpresas() {
        givenRows(
            row(1L, "ACME", "Premium", 8000, 10),
            row(2L, "Globex", "Premium", 9000, 5)
        );

        BillingPeriod report = service.report(DESDE, HASTA, null);

        assertThat(report.empresas()).hasSize(2);
        assertThat(report.totalGeneral()).isEqualTo(10 * 8000L + 5 * 9000L);
        assertThat(report.totalPedidos()).isEqualTo(15);
    }

    @Test
    @DisplayName("si la tarifa cambió en el período, cada precio se cobra por separado")
    void tarifaRenegociadaEnElMedioDelPeriodo() {
        // Misma categoría, dos precios congelados distintos: se renegoció el miércoles.
        givenRows(
            row(1L, "ACME", "Premium", 8000, 6),
            row(1L, "ACME", "Premium", 9500, 4)
        );

        BillingPeriod report = service.report(DESDE, HASTA, null);

        CompanyBilling acme = report.empresas().getFirst();
        assertThat(acme.lineas()).hasSize(2);
        assertThat(acme.total()).isEqualTo(6 * 8000L + 4 * 9500L);
    }

    @Test
    @DisplayName("pedidos sin precio congelado se cuentan como sin tarifa y no suman")
    void pedidosSinTarifaNoSuman() {
        givenRows(
            row(1L, "ACME", "Premium", 8000, 10),
            row(1L, "ACME", "Basico", null, 3)   // anteriores a V14
        );

        BillingPeriod report = service.report(DESDE, HASTA, null);

        CompanyBilling acme = report.empresas().getFirst();
        assertThat(acme.total()).isEqualTo(80000L);
        assertThat(acme.totalPedidos()).isEqualTo(13);
        assertThat(acme.pedidosSinTarifa()).isEqualTo(3);
        assertThat(report.pedidosSinTarifa()).isEqualTo(3);
    }

    @Test
    @DisplayName("precio 0 también cuenta como sin tarifa — se sirvió y no se cobra")
    void precioCeroEsAlerta() {
        givenRows(row(1L, "ACME", "Premium", 0, 7));

        BillingPeriod report = service.report(DESDE, HASTA, null);

        assertThat(report.pedidosSinTarifa()).isEqualTo(7);
        assertThat(report.totalGeneral()).isZero();
    }

    @Test
    @DisplayName("usa el nombre actual de la categoría, y el congelado si fue borrada")
    void nombreDeCategoriaConFallback() {
        BillingRow renombrada = new BillingRow(1L, "ACME", 10L, "Premium Plus", "Premium", 8000, 2L);
        BillingRow borrada = new BillingRow(1L, "ACME", null, null, "Viejo Tier", 5000, 1L);
        when(repo.aggregateBilling(any(), any(), any())).thenReturn(List.of(renombrada, borrada));

        BillingPeriod report = service.report(DESDE, HASTA, null);

        assertThat(report.empresas().getFirst().lineas())
            .extracting(BillingDtos.CategoryLine::categoria)
            .containsExactly("Premium Plus", "Viejo Tier");
    }

    @Test
    @DisplayName("período sin pedidos devuelve totales en cero, no falla")
    void periodoVacio() {
        givenRows();

        BillingPeriod report = service.report(DESDE, HASTA, null);

        assertThat(report.empresas()).isEmpty();
        assertThat(report.totalGeneral()).isZero();
        assertThat(report.totalPedidos()).isZero();
    }

    @Test
    @DisplayName("companyId null baja a la query como null — todas las empresas")
    void sinFiltroPasaNullALaQuery() {
        givenRows(row(1L, "ACME", "Premium", 8000, 2));

        service.report(DESDE, HASTA, null);

        verify(repo).aggregateBilling(DESDE, HASTA, null);
    }

    @Test
    @DisplayName("el filtro por empresa baja a la query, no se resuelve en memoria")
    void filtroPorEmpresaBajaALaQuery() {
        givenRows(row(2L, "Globex", "Premium", 9000, 5));

        BillingPeriod report = service.report(DESDE, HASTA, 2L);

        verify(repo).aggregateBilling(DESDE, HASTA, 2L);
        assertThat(report.empresas()).hasSize(1);
        assertThat(report.empresas().getFirst().companyNombre()).isEqualTo("Globex");
    }

    @Test
    @DisplayName("filtrado, el total del período es el de esa empresa — no el global")
    void totalRespetaElFiltro() {
        // La query ya devuelve solo Globex; el total no puede arrastrar otras empresas.
        givenRows(row(2L, "Globex", "Premium", 9000, 5));

        BillingPeriod report = service.report(DESDE, HASTA, 2L);

        assertThat(report.totalGeneral()).isEqualTo(45000L);
        assertThat(report.totalPedidos()).isEqualTo(5);
        assertThat(report.totalGeneral())
            .isEqualTo(report.empresas().getFirst().total());
    }

    @Test
    @DisplayName("filtrar una empresa sin pedidos devuelve vacío, no falla")
    void filtroSinResultados() {
        givenRows();

        BillingPeriod report = service.report(DESDE, HASTA, 99L);

        assertThat(report.empresas()).isEmpty();
        assertThat(report.totalGeneral()).isZero();
    }

    @Test
    @DisplayName("devuelve los totales diarios en orden, solo días con pedidos")
    void totalesDiarios() {
        givenRows(row(1L, "ACME", "Premium", 8000, 5));
        givenDaily(dia(3, 2, 16000), dia(5, 3, 24000));   // el 4 no tuvo pedidos

        BillingPeriod report = service.report(DESDE, HASTA, null);

        assertThat(report.porDia())
            .extracting(BillingDtos.DailyTotal::fecha)
            .containsExactly(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5));
        assertThat(report.porDia().getFirst().total()).isEqualTo(16000);
        assertThat(report.porDia().getFirst().pedidos()).isEqualTo(2);
    }

    @Test
    @DisplayName("el corte diario y el corte por empresa suman lo mismo")
    void losDosCortesCoinciden() {
        // Mismos pedidos vistos de dos formas: 5 Premium a 8000 = 40000.
        givenRows(row(1L, "ACME", "Premium", 8000, 5));
        givenDaily(dia(3, 2, 16000), dia(5, 3, 24000));

        BillingPeriod report = service.report(DESDE, HASTA, null);

        long sumaDiaria = report.porDia().stream()
            .mapToLong(BillingDtos.DailyTotal::total).sum();
        long pedidosDiarios = report.porDia().stream()
            .mapToLong(BillingDtos.DailyTotal::pedidos).sum();

        assertThat(sumaDiaria).isEqualTo(report.totalGeneral());
        assertThat(pedidosDiarios).isEqualTo(report.totalPedidos());
    }

    @Test
    @DisplayName("el filtro por empresa también se aplica al corte diario")
    void filtroLlegaAlCorteDiario() {
        givenRows(row(2L, "Globex", "Premium", 9000, 5));
        givenDaily(dia(3, 5, 45000));

        service.report(DESDE, HASTA, 2L);

        verify(repo).aggregateDailyTotals(DESDE, HASTA, 2L);
    }

    @Test
    @DisplayName("período sin pedidos devuelve la serie diaria vacía")
    void serieDiariaVacia() {
        givenRows();
        givenDaily();

        BillingPeriod report = service.report(DESDE, HASTA, null);

        assertThat(report.porDia()).isEmpty();
    }

    @Test
    @DisplayName("rechaza rangos invertidos y demasiado amplios")
    void validaRango() {
        assertThatThrownBy(() -> service.report(HASTA, DESDE, null))
            .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> service.report(DESDE, DESDE.plusYears(3), null))
            .isInstanceOf(BusinessException.class);
    }
}
