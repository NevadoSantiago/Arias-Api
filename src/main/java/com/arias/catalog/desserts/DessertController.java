package com.arias.catalog.desserts;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/desserts")
@RequiredArgsConstructor
public class DessertController {

    private final DessertService service;

    /** Postres habilitados para elegir como premio; los de stock 0 vienen igual (el front los marca). */
    @GetMapping("/available")
    @PreAuthorize("isAuthenticated()")
    public List<DessertDto> available() {
        return service.listAvailable();
    }
}
