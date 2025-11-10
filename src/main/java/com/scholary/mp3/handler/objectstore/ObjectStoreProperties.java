package com.scholary.mp3.handler.objectstore;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for object storage.
 *
 * <p>These map to the "objectstore.*" keys in application.yml. Spring Boot will automatically bind
 * and validate them at startup.
 */
@ConfigurationProperties(prefix = "objectstore")
@Validated
public record ObjectStoreProperties(
    @NotBlank String endpoint,
    @NotBlank String accessKey,
    @NotBlank String secretKey,
    @NotBlank String bucket,
    String region,
    boolean pathStyleAccess) {}
