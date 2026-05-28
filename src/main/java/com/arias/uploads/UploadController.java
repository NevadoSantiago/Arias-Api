package com.arias.uploads;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint para que el SUPER_ADMIN obtenga URLs firmadas de upload a R2.
 *
 * <p>El backend NO recibe los bytes de la imagen. Solo genera la URL firmada;
 * el browser sube directo a R2 con esa URL.
 */
@RestController
@RequestMapping("/api/v1/admin/uploads")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class UploadController {

    private final UploadService uploadService;

    @PostMapping("/dish-photo")
    public UploadResponse presignDishPhoto(@Valid @RequestBody UploadRequest req) {
        return uploadService.presignDishPhotoUpload(req);
    }
}
