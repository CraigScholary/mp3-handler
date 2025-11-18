package com.scholary.mp3.handler.config;

import jakarta.validation.Valid;
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
    @Valid ChunkingProperties chunking,
    @Valid OverlapProperties overlap,
    @Valid MergeProperties merge,
    @Positive int maxFileDurationHours,
    @NotBlank String tempDir,
    @Positive int asyncExecutorThreads,
    @Positive int asyncExecutorQueueSize) {
    
    public record ChunkingProperties(
        @Positive int defaultChunkSeconds,
        boolean defaultSilenceAware) {}
    
    public record OverlapProperties(
        @Positive int overlapSeconds,
        @Positive int minOverlapWords) {}
    
    public record MergeProperties(
        @Positive int contextWindowWords,
        @Positive double minSimilarityThreshold) {}
}
