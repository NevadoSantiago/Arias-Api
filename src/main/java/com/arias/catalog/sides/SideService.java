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
            .sorted(Comparator
                .comparing(Side::getTipo)
                .thenComparing(s -> s.getNombre().toLowerCase()))
            .map(SideDto::from)
            .toList();
    }

    @Transactional
    public SideDto create(CreateSideRequest req) {
        if (repo.findAll().stream()
            .anyMatch(s -> s.getNombre().equalsIgnoreCase(req.nombre()))) {
            throw BusinessException.conflict("side-name-duplicate",
                "Ya existe un acompañamiento con ese nombre");
        }

        Side side = Side.builder()
            .nombre(req.nombre().trim())
            .tipo(req.tipo())
            .enabled(true)
            .build();
        return SideDto.from(repo.save(side));
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

    private Side findOrThrow(Long id) {
        return repo.findById(id)
            .orElseThrow(() -> BusinessException.notFound("side-not-found",
                "Acompañamiento no encontrado"));
    }
}
