package com.arias.uploads;

/**
 * Respuesta del upload presign:
 *  - uploadUrl: URL firmada PUT, válida por unos minutos. El browser sube acá.
 *  - publicUrl: URL final pública (vía r2.dev o dominio custom). Se persiste en
 *    Dish.fotoUrl una vez confirmado el upload.
 *  - key: identifier interno del objeto en el bucket.
 */
public record UploadResponse(
    String uploadUrl,
    String publicUrl,
    String key
) {}
