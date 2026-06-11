package com.arias.catalog.sides;

import com.arias.common.exception.BusinessException;
import com.arias.email.DisableNotificationEmails;
import com.arias.email.DisableNotificationEmails.Reason;
import com.arias.orders.AffectedOrderDto;
import com.arias.orders.DailyChoice;
import com.arias.orders.DailyChoiceRepository;
import com.arias.restaurantconfig.RestaurantConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SideService {

    private final SideRepository repo;
    private final DailyChoiceRepository orderRepo;
    private final RestaurantConfigRepository configRepo;
    private final DisableNotificationEmails emails;

    @Transactional(readOnly = true)
    public List<SideDto> listAll() {
        return repo.findAll().stream()
            .filter(s -> s.getDeletedAt() == null)
            .sorted(Comparator
                .comparing(Side::getTipo)
                .thenComparing(s -> s.getNombre().toLowerCase()))
            .map(SideDto::from)
            .toList();
    }

    @Transactional
    public SideDto create(CreateSideRequest req) {
        String nombre = req.nombre().trim();
        Side existing = repo.findAll().stream()
            .filter(s -> s.getNombre().equalsIgnoreCase(nombre))
            .findFirst()
            .orElse(null);

        if (existing != null) {
            if (existing.getDeletedAt() == null) {
                throw BusinessException.conflict("side-name-duplicate",
                    "Ya existe un acompañamiento con ese nombre");
            }
            return SideDto.from(resurrect(existing, nombre, req.tipo()));
        }

        Side side = Side.builder()
            .nombre(nombre)
            .tipo(req.tipo())
            .enabled(true)
            .build();
        return SideDto.from(repo.save(side));
    }

    /**
     * Crear un side con el nombre de uno archivado lo RESUCITA en vez de
     * chocar con el UNIQUE de nombre. Reusar la fila preserva el historial
     * (daily_choice.side_id) intacto.
     *
     * <p>Si el tipo cambia (era guarnición, vuelve como salsa), las
     * asociaciones viejas en dish_side quedarían con un tipo que el plato
     * no admite — se limpian y el admin lo asocia de nuevo donde quiera.
     */
    private Side resurrect(Side side, String nombre, SideType tipo) {
        if (side.getTipo() != tipo) {
            repo.removeFromAllDishes(side.getId());
        }
        side.setNombre(nombre);
        side.setTipo(tipo);
        side.setEnabled(true);
        side.setDeletedAt(null);
        return side;
    }

    @Transactional
    public SideDto update(Long id, UpdateSideRequest req) {
        Side side = findOrThrow(id);

        if (!side.getNombre().equalsIgnoreCase(req.nombre())) {
            boolean duplicate = repo.findAll().stream()
                .anyMatch(s -> !s.getId().equals(id) && s.getNombre().equalsIgnoreCase(req.nombre()));
            if (duplicate) {
                throw BusinessException.conflict("side-name-duplicate",
                    "Ya existe otro acompañamiento con ese nombre");
            }
        }

        side.setNombre(req.nombre().trim());
        side.setTipo(req.tipo());
        side.setEnabled(req.enabled());
        return SideDto.from(side);
    }

    /** Pedidos PENDIENTE que se verían afectados si desactivamos este side. */
    @Transactional(readOnly = true)
    public List<AffectedOrderDto> findAffectedOrders(Long id) {
        findOrThrow(id);
        return orderRepo.findPendingBySide(id).stream()
            .map(AffectedOrderDto::from)
            .toList();
    }

    /**
     * Desactiva el side y, a diferencia de los platos, NUNCA cancela los
     * pedidos: el plato sigue siendo válido sin acompañamiento (los snapshots
     * tienen sideNombre, la cocina sabe qué hacer). Solo notifica por email.
     */
    @Transactional
    public void disable(Long id) {
        Side side = findOrThrow(id);
        side.setEnabled(false);

        List<DailyChoice> affected = orderRepo.findPendingBySide(id);
        LocalTime cutoff = configRepo.getSingleton().getHoraCorte();

        for (DailyChoice order : affected) {
            emails.notifyOrderKept(order, Reason.SIDE_DISABLED, cutoff);
        }
    }

    @Transactional
    public void enable(Long id) {
        findOrThrow(id).setEnabled(true);
    }

    /**
     * Soft-delete del acompañamiento. Requiere estar deshabilitado primero
     * (2-step disable → delete, mismo patrón que Dish/Category). Los pedidos
     * históricos conservan el snapshot sideNombre y el FK side_id intacto.
     */
    @Transactional
    public void archive(Long id) {
        Side side = findOrThrow(id);
        if (Boolean.TRUE.equals(side.getEnabled())) {
            throw BusinessException.badRequest("must-disable-first",
                "Primero desactivá el acompañamiento, después podés borrarlo");
        }
        side.setDeletedAt(Instant.now());
    }

    private Side findOrThrow(Long id) {
        return repo.findById(id)
            .filter(s -> s.getDeletedAt() == null)
            .orElseThrow(() -> BusinessException.notFound("side-not-found",
                "Acompañamiento no encontrado"));
    }
}
