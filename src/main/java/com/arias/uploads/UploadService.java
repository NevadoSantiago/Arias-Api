package com.arias.uploads;

import com.arias.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.util.UUID;

/**
 * Genera URLs firmadas para que el frontend suba imágenes DIRECTO a R2.
 *
 * <p>El presigner se inyecta como {@link ObjectProvider} para tolerar el caso
 * donde R2 no está configurado — si está sin setear, devolvemos error claro
 * (503) en vez de fallar el arranque de toda la app.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UploadService {

    private final R2Properties props;
    /** Optional — null si R2 no está configurado. */
    private final ObjectProvider<S3Presigner> presignerProvider;

    public UploadResponse presignDishPhotoUpload(UploadRequest req) {
        if (!props.isConfigured()) {
            throw new BusinessException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "uploads-disabled",
                "El upload de imágenes no está configurado. Contactá al administrador del sistema."
            );
        }

        S3Presigner presigner = presignerProvider.getIfAvailable();
        if (presigner == null) {
            // No debería ocurrir si isConfigured() pasa, pero defensivo
            throw new BusinessException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "uploads-disabled",
                "El cliente de R2 no está disponible."
            );
        }

        String extension = extractExtension(req.fileName(), req.contentType());
        String key = "dishes/" + UUID.randomUUID() + "." + extension;

        PutObjectRequest objectRequest = PutObjectRequest.builder()
            .bucket(props.bucket())
            .key(key)
            .contentType(req.contentType())
            .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(props.presignTtl())
            .putObjectRequest(objectRequest)
            .build();

        String uploadUrl = presigner.presignPutObject(presignRequest).url().toString();
        String publicUrl = props.publicUrl().replaceAll("/+$", "") + "/" + key;

        log.info("Presigned upload generado: key={} ttl={}", key, props.presignTtl());
        return new UploadResponse(uploadUrl, publicUrl, key);
    }

    /**
     * Resuelve la extensión: priorizamos la del fileName si es válida,
     * si no derivamos del content-type. Garantizamos extensión minúscula y
     * sin caracteres raros.
     */
    private String extractExtension(String fileName, String contentType) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot + 1).toLowerCase();
            if (ext.matches("[a-z0-9]{1,5}")) return ext;
        }
        // Fallback desde content-type
        return switch (contentType) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "bin";
        };
    }
}
