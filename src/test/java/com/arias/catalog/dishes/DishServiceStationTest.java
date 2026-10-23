package com.arias.catalog.dishes;

import com.arias.catalog.categories.Category;
import com.arias.catalog.categories.CategoryHierarchyService;
import com.arias.catalog.categories.CategoryRepository;
import com.arias.catalog.menusections.MenuSection;
import com.arias.catalog.menusections.MenuSectionRepository;
import com.arias.catalog.sides.SideRepository;
import com.arias.email.DisableNotificationEmails;
import com.arias.orders.DailyChoiceRepository;
import com.arias.restaurantconfig.RestaurantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Dish station (COCINA / PARRILLA) through the admin create/update service. No Spring. */
class DishServiceStationTest {

    private DishRepository dishRepo;
    private DishService service;

    @BeforeEach
    void setUp() {
        dishRepo = mock(DishRepository.class);
        CategoryRepository categoryRepo = mock(CategoryRepository.class);
        MenuSectionRepository menuSectionRepo = mock(MenuSectionRepository.class);
        service = new DishService(dishRepo, categoryRepo, menuSectionRepo, mock(SideRepository.class),
            mock(CategoryHierarchyService.class), mock(DailyChoiceRepository.class),
            mock(RestaurantConfigRepository.class), mock(DisableNotificationEmails.class));

        Category category = Category.builder().id(1L).nombre("Premium").enabled(true).build();
        MenuSection section = MenuSection.builder().id(2L).nombre("Carnes").ordenDisplay(0).enabled(true).build();
        when(categoryRepo.findById(1L)).thenReturn(Optional.of(category));
        when(menuSectionRepo.findById(2L)).thenReturn(Optional.of(section));
        when(dishRepo.save(any(Dish.class))).thenAnswer(inv -> {
            Dish d = inv.getArgument(0);
            d.setId(10L);
            return d;
        });
    }

    private CreateDishRequest create(DishStation station) {
        return new CreateDishRequest("Bife", null, null, 1L, 2L, null, null, 5, 5, false, station);
    }

    private UpdateDishRequest update(DishStation station) {
        return new UpdateDishRequest("Bife", null, null, 1L, 2L, null, null, 5, 5, true, false, station);
    }

    private Dish existing(DishStation station) {
        Dish d = Dish.builder().id(10L).nombre("Bife").station(station)
            .category(Category.builder().id(1L).nombre("Premium").enabled(true).build())
            .menuSection(MenuSection.builder().id(2L).nombre("Carnes").ordenDisplay(0).enabled(true).build())
            .allowedSides(new HashSet<>()).stockDiarioDefault(5).stockActual(5)
            .enabled(true).especial(false).build();
        when(dishRepo.findById(10L)).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    @DisplayName("create with PARRILLA stores and exposes the station")
    void createWithParrilla() {
        AdminDishDto dto = service.create(create(DishStation.PARRILLA));
        assertThat(dto.station()).isEqualTo(DishStation.PARRILLA);
    }

    @Test
    @DisplayName("create without station defaults to COCINA")
    void createWithoutStationDefaultsToCocina() {
        AdminDishDto dto = service.create(create(null));
        assertThat(dto.station()).isEqualTo(DishStation.COCINA);
    }

    @Test
    @DisplayName("update changes the station")
    void updateChangesStation() {
        existing(DishStation.COCINA);
        AdminDishDto dto = service.update(10L, update(DishStation.PARRILLA));
        assertThat(dto.station()).isEqualTo(DishStation.PARRILLA);
    }

    @Test
    @DisplayName("update without station keeps the current one")
    void updateWithoutStationKeepsCurrent() {
        existing(DishStation.PARRILLA);
        AdminDishDto dto = service.update(10L, update(null));
        assertThat(dto.station()).isEqualTo(DishStation.PARRILLA);
    }

    @Test
    @DisplayName("a Dish built without station is COCINA")
    void builderDefaultIsCocina() {
        assertThat(Dish.builder().build().getStation()).isEqualTo(DishStation.COCINA);
    }
}
