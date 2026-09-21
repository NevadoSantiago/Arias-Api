package com.arias.catalog.categories;

import com.arias.catalog.dishes.DishRepository;
import com.arias.common.exception.BusinessException;
import com.arias.companies.CompanyCategoryPriceRepository;
import com.arias.companies.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests de {@code creditCost} en {@link CategoryService} — spec catalog-credit-pricing.
 * Sin Spring: se mockean los repos, igual que {@code BillingServiceTest}.
 */
class CategoryServiceTest {

    private CategoryRepository repo;
    private CompanyRepository companyRepo;
    private CompanyCategoryPriceRepository priceRepo;
    private DishRepository dishRepo;
    private CategoryService service;

    @BeforeEach
    void setUp() {
        repo = mock(CategoryRepository.class);
        companyRepo = mock(CompanyRepository.class);
        priceRepo = mock(CompanyCategoryPriceRepository.class);
        dishRepo = mock(DishRepository.class);
        service = new CategoryService(repo, companyRepo, priceRepo, dishRepo);

        when(companyRepo.findAll()).thenReturn(List.of());
        when(repo.findByNombreIncludingDeleted(anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });
    }

    private CreateCategoryRequest createRequest(String nombre, Integer creditCost) {
        return new CreateCategoryRequest(nombre, null, 0, creditCost, Map.of());
    }

    private UpdateCategoryRequest updateRequest(String nombre, Integer creditCost) {
        return new UpdateCategoryRequest(nombre, null, 0, true, creditCost, Map.of());
    }

    @Test
    @DisplayName("categoría estándar: creditCost por defecto es 1")
    void categoriaEstandarCreditCostPorDefecto() {
        Category estandar = Category.builder()
            .id(1L)
            .nombre("Estándar")
            .ordenDisplay(0)
            .enabled(true)
            .build();

        assertThat(estandar.getCreditCost()).isEqualTo(1);
    }

    @Test
    @DisplayName("crea una categoría con el creditCost solicitado")
    void creaCategoriaConCreditCost() {
        AdminCategoryDto dto = service.create(createRequest("Premium", 3));

        assertThat(dto.creditCost()).isEqualTo(3);
    }

    @Test
    @DisplayName("edita el creditCost de una categoría existente")
    void editaCreditCostDeCategoriaExistente() {
        Category existente = Category.builder()
            .id(5L)
            .nombre("Básico")
            .ordenDisplay(0)
            .enabled(true)
            .creditCost(1)
            .build();
        when(repo.findById(5L)).thenReturn(Optional.of(existente));
        when(repo.findByNombreIncludingDeleted("Básico")).thenReturn(Optional.of(existente));

        AdminCategoryDto dto = service.update(5L, updateRequest("Básico", 2));

        assertThat(dto.creditCost()).isEqualTo(2);
        assertThat(existente.getCreditCost()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -5})
    @DisplayName("rechaza creditCost cero o negativo al crear")
    void rechazaCreditCostNoPositivoAlCrear(int invalido) {
        assertThatThrownBy(() -> service.create(createRequest("Premium", invalido)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("rechaza creditCost nulo al crear")
    void rechazaCreditCostNuloAlCrear() {
        assertThatThrownBy(() -> service.create(createRequest("Premium", null)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("rechaza creditCost cero o negativo al editar")
    void rechazaCreditCostNoPositivoAlEditar() {
        Category existente = Category.builder()
            .id(5L)
            .nombre("Básico")
            .ordenDisplay(0)
            .enabled(true)
            .creditCost(1)
            .build();
        when(repo.findById(5L)).thenReturn(Optional.of(existente));
        when(repo.findByNombreIncludingDeleted("Básico")).thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> service.update(5L, updateRequest("Básico", 0)))
            .isInstanceOf(BusinessException.class);

        // El saldo previo no se pierde: la escritura inválida nunca se aplicó.
        assertThat(existente.getCreditCost()).isEqualTo(1);
    }
}
