package com.arias.uploads;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UploadRequest(
    @NotBlank @Size(max = 255) String fileName,
    /** Solo aceptamos image/* — defensa en el backend además de en el front. */
    @NotBlank @Pattern(regexp = "^image/(jpeg|jpg|png|webp|gif)$",
        message = "Tipo de imagen no permitido (solo jpeg, png, webp o gif)")
    String contentType
) {}
