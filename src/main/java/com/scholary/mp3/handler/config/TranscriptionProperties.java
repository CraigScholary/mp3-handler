package com.scholary.mp3.handler.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for transcription processing.
 *
 * <p>Controls default values, limits, and resource allocation for transcription jobs.
 */
@ConfigurationProperties(prefix = "transcription")
@Validated
public record TranscriptionProperties(
    @Positive int defaultChunkSeconds,
    @Positive int defaultOverlapSeconds,
    boolean defaultSilenceAware,
    @Positive int maxFileDurationHours,
    @NotBlank String tempDir,
    @Positive int asyncExecutorThreads,
    @Positive int asyncExecutorQueueSize) {}
