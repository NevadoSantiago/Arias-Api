package com.arias.credits.packs;

import com.arias.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tipo de paquete (INDIVIDUAL / SUGERIDO / OTRO): derivación código↔tipo en
 * la creación, unicidad de INDIVIDUAL y SUGERIDO, y el índice parcial de la
 * migración V30. {@code @Transactional} hace rollback: la base compartida
 * queda intacta.
 */
@SpringBootTest
@Transactional
class CreditPackServiceTest {

    @Autowired
    private CreditPackService service;

    @Autowired
    private CreditPackRepository repo;

    /** La base de tests es compartida: se aparta (dentro del rollback) todo paquete vivo previo. */
    @BeforeEach
    void clearLivePacks() {
        repo.findAllByDeletedAtIsNullOrderByOrdenDisplayAsc().forEach(p -> {
            p.setDeletedAt(Instant.now());
            p.setCode("OLD-" + p.getId());
        });
        repo.flush();
    }

    private CreateCreditPackRequest req(String code, CreditPackType type, String nombre) {
        return new CreateCreditPackRequest(code, type, nombre, 5, 10_000L, 0, 0);
    }

    @Test
    @DisplayName("create(): INDIVIDUAL sin código genera DAY")
    void individualWithoutCodeGeneratesDay() {
        CreditPackDto dto = service.create(req(null, CreditPackType.INDIVIDUAL, "Día"));
        assertThat(dto.code()).isEqualTo("DAY");
        assertThat(dto.packType()).isEqualTo(CreditPackType.INDIVIDUAL);
    }

    @Test
    @DisplayName("create(): SUGERIDO sin código genera WEEK")
    void sugeridoWithoutCodeGeneratesWeek() {
        CreditPackDto dto = service.create(req(" ", CreditPackType.SUGERIDO, "Semana"));
        assertThat(dto.code()).isEqualTo("WEEK");
        assertThat(dto.packType()).isEqualTo(CreditPackType.SUGERIDO);
    }

    @Test
    @DisplayName("create(): OTRO sin código genera códigos únicos de hasta 20 caracteres")
    void otroWithoutCodeGeneratesUniqueCodes() {
        CreditPackDto a = service.create(req(null, CreditPackType.OTRO, "Un nombre de paquete bastante largo"));
        CreditPackDto b = service.create(req(null, CreditPackType.OTRO, "Un nombre de paquete bastante largo"));
        assertThat(a.packType()).isEqualTo(CreditPackType.OTRO);
        assertThat(a.code()).isNotBlank().hasSizeLessThanOrEqualTo(20);
        assertThat(b.code()).isNotBlank().hasSizeLessThanOrEqualTo(20).isNotEqualTo(a.code());
    }

    @Test
    @DisplayName("create(): sin tipo (cliente viejo) lo deriva del código DAY/WEEK/otro")
    void typeIsDerivedFromCode() {
        assertThat(service.create(req("day", null, "Día")).packType()).isEqualTo(CreditPackType.INDIVIDUAL);
        assertThat(service.create(req("WEEK", null, "Semana")).packType()).isEqualTo(CreditPackType.SUGERIDO);
        assertThat(service.create(req("MONTH", null, "Mes")).packType()).isEqualTo(CreditPackType.OTRO);
    }

    @Test
    @DisplayName("create(): código y tipo contradictorios responden 400")
    void contradictoryCodeAndTypeIs400() {
        assertThatThrownBy(() -> service.create(req("DAY", CreditPackType.OTRO, "Día")))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getErrorCode()).isEqualTo("credit-pack-type-code-mismatch");
            });
        assertThatThrownBy(() -> service.create(req("WEEK", CreditPackType.INDIVIDUAL, "Semana")))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("credit-pack-type-code-mismatch"));
    }

    @Test
    @DisplayName("create(): un segundo INDIVIDUAL o SUGERIDO responde 409")
    void secondIndividualOrSugeridoIs409() {
        service.create(req(null, CreditPackType.INDIVIDUAL, "Día"));
        service.create(req(null, CreditPackType.SUGERIDO, "Semana"));
        assertThatThrownBy(() -> service.create(req("OTHER1", CreditPackType.INDIVIDUAL, "Otro día")))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getErrorCode()).isEqualTo("credit-pack-type-duplicate");
            });
        assertThatThrownBy(() -> service.create(req("OTHER2", CreditPackType.SUGERIDO, "Otra semana")))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("credit-pack-type-duplicate"));
    }

    @Test
    @DisplayName("create(): tras borrar el INDIVIDUAL se puede crear otro, aunque DAY esté tomado")
    void individualCanBeRecreatedAfterDelete() {
        CreditPackDto first = service.create(req(null, CreditPackType.INDIVIDUAL, "Día"));
        service.delete(first.id());
        CreditPackDto second = service.create(req(null, CreditPackType.INDIVIDUAL, "Día nuevo"));
        assertThat(second.packType()).isEqualTo(CreditPackType.INDIVIDUAL);
        assertThat(second.code()).isNotEqualTo("DAY").hasSizeLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("update(): el tipo no se modifica")
    void updateKeepsType() {
        CreditPackDto created = service.create(req(null, CreditPackType.SUGERIDO, "Semana"));
        CreditPackDto updated = service.update(created.id(),
            new UpdateCreditPackRequest("Semana 2", 6, 12_000L, 5, 1, true));
        assertThat(updated.packType()).isEqualTo(CreditPackType.SUGERIDO);
        assertThat(updated.code()).isEqualTo("WEEK");
    }

    @Test
    @DisplayName("listados público y admin exponen packType")
    void listingsExposePackType() {
        service.create(req(null, CreditPackType.SUGERIDO, "Semana"));
        assertThat(service.listPublic()).extracting(CreditPackDto::packType)
            .containsExactly(CreditPackType.SUGERIDO);
        assertThat(service.listAdmin()).extracting(CreditPackDto::packType)
            .containsExactly(CreditPackType.SUGERIDO);
    }

    @Test
    @DisplayName("índice parcial: la base rechaza un segundo INDIVIDUAL vivo pero admite uno borrado")
    void partialUniqueIndexEnforcedByDatabase() {
        repo.saveAndFlush(pack("T-A", CreditPackType.INDIVIDUAL, null));
        repo.saveAndFlush(pack("T-B", CreditPackType.INDIVIDUAL, Instant.now()));
        assertThatThrownBy(() -> repo.saveAndFlush(pack("T-C", CreditPackType.INDIVIDUAL, null)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CreditPack pack(String code, CreditPackType type, Instant deletedAt) {
        return CreditPack.builder().code(code).packType(type).nombre("t").creditAmount(1)
            .priceCents(100L).deletedAt(deletedAt).build();
    }
}
