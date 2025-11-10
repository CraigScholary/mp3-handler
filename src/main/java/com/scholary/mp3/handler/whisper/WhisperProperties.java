package com.scholary.mp3.handler.whisper;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Whisper API client.
 *
 * <p>These control how we connect to the Whisper service (or mock) and handle timeouts/retries.
 */
@ConfigurationProperties(prefix = "whisper")
@Validated
public record WhisperProperties(
    @NotBlank String baseUrl,
    @Positive int connectTimeout,
    @Positive int readTimeout,
    @Positive int maxRetries) {}
