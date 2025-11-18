package com.scholary.mp3.handler.api;

import com.scholary.mp3.handler.strategy.ChunkingConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request for transcription with strategy selection.
 *
 * <p>Version 2 of the API with explicit mode selection.
 */
public record TranscriptionRequestV2(
    @NotBlank String bucket,
    @NotBlank String key,
    @NotNull ChunkingMode mode,
    @NotNull ChunkingConfig chunkingConfig,
    boolean save) {

  /**
   * Create request for overlap mode.
   */
  public static TranscriptionRequestV2 forOverlapMode(
      String bucket, String key, double maxChunkDuration, double overlapSeconds, boolean save) {
    return new TranscriptionRequestV2(
        bucket,
        key,
        ChunkingMode.OVERLAP,
        ChunkingConfig.forOverlapMode(maxChunkDuration, overlapSeconds),
        save);
  }

  /**
   * Create request for silence-aware mode.
   */
  public static TranscriptionRequestV2 forSilenceAwareMode(
      String bucket,
      String key,
      double maxChunkDuration,
      String silenceThreshold,
      double silenceMinDuration,
      double lookbackSeconds,
      boolean save) {
    return new TranscriptionRequestV2(
        bucket,
        key,
        ChunkingMode.SILENCE_AWARE,
        ChunkingConfig.forSilenceAwareMode(
            maxChunkDuration, silenceThreshold, silenceMinDuration, lookbackSeconds),
        save);
  }
}
