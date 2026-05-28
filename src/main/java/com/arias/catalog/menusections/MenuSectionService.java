package com.arias.catalog.menusections;

import com.arias.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MenuSectionService {

    private final MenuSectionRepository repo;

    @Transactional(readOnly = true)
    public List<MenuSectionDto> listAll() {
        return repo.findAll().stream()
            .sorted((a, b) -> {
                int byOrden = Integer.compare(a.getOrdenDisplay(), b.getOrdenDisplay());
                return byOrden != 0 ? byOrden : a.getNombre().compareToIgnoreCase(b.getNombre());
            })
            .map(MenuSectionDto::from)
            .toList();
    }

    @Transactional
    public MenuSectionDto create(CreateMenuSectionRequest req) {
        repo.findByNombre(req.nombre()).ifPresent(s -> {
            throw BusinessException.conflict("section-name-duplicate",
                "Ya existe una sección con ese nombre");
        });

        MenuSection section = MenuSection.builder()
            .nombre(req.nombre().trim())
            .ordenDisplay(req.ordenDisplay())
            .enabled(true)
            .build();
        return MenuSectionDto.from(repo.save(section));
    }

    @Transactional
    public MenuSectionDto update(Long id, UpdateMenuSectionRequest req) {
        MenuSection section = findOrThrow(id);

        // Si cambió el nombre, verificar que no choque con otra sección
        if (!section.getNombre().equalsIgnoreCase(req.nombre())) {
            repo.findByNombre(req.nombre()).ifPresent(other -> {
                if (!other.getId().equals(id)) {
                    throw BusinessException.conflict("section-name-duplicate",
                        "Ya existe otra sección con ese nombre");
                }
            });
        }

        section.setNombre(req.nombre().trim());
        section.setOrdenDisplay(req.ordenDisplay());
        section.setEnabled(req.enabled());
        return MenuSectionDto.from(section);
    }

    @Transactional
    public void disable(Long id) {
        findOrThrow(id).setEnabled(false);
    }

    @Transactional
    public void enable(Long id) {
        findOrThrow(id).setEnabled(true);
    }

    private MenuSection findOrThrow(Long id) {
        return repo.findById(id)
            .orElseThrow(() -> BusinessException.notFound("section-not-found",
                "Sección no encontrada"));
    }
}
