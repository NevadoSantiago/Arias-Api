package com.arias.uploads;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Beans del cliente S3 apuntando a Cloudflare R2.
 *
 * <p>Solo se levantan si {@code arias.r2.endpoint} está seteado — así no rompe
 * el arranque de la app en entornos sin R2 configurado (el endpoint de upload
 * responderá con 503 + mensaje claro).
 */
@Configuration
@ConditionalOnProperty(name = "arias.r2.endpoint")
@Slf4j
public class R2Config {

    /**
     * R2 ignora la región pero el SDK la requiere — usamos "auto" como convención
     * documentada por Cloudflare.
     */
    private static final Region R2_REGION = Region.of("auto");

    @Bean
    public S3Client s3Client(R2Properties props) {
        log.info("Inicializando S3Client para Cloudflare R2 (bucket={})", props.bucket());
        return S3Client.builder()
            .region(R2_REGION)
            .endpointOverride(URI.create(props.endpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKeyId(), props.secretAccessKey())))
            // R2 requiere path-style addressing (https://endpoint/bucket/key
            // en vez del default virtual-host https://bucket.endpoint/key)
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build())
            .build();
    }

    @Bean
    public S3Presigner s3Presigner(R2Properties props) {
        return S3Presigner.builder()
            .region(R2_REGION)
            .endpointOverride(URI.create(props.endpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKeyId(), props.secretAccessKey())))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build())
            .build();
    }
}
