package com.scholary.mp3.handler.strategy;

/**
 * Configuration for chunking strategies.
 *
 * <p>Contains strategy-specific settings.
 */
public record ChunkingConfig(
    // Common settings
    double maxChunkDurationSeconds,

    // Overlap mode settings
    Double overlapSeconds,

    // Silence-aware mode settings
    String silenceThreshold,
    Double silenceMinDuration,
    Double lookbackSeconds) {

  /**
   * Create config for overlap mode.
   */
  public static ChunkingConfig forOverlapMode(double maxChunkDuration, double overlapSeconds) {
    return new ChunkingConfig(maxChunkDuration, overlapSeconds, null, null, null);
  }

  /**
   * Create config for silence-aware mode.
   */
  public static ChunkingConfig forSilenceAwareMode(
      double maxChunkDuration,
      String silenceThreshold,
      double silenceMinDuration,
      double lookbackSeconds) {
    return new ChunkingConfig(
        maxChunkDuration, null, silenceThreshold, silenceMinDuration, lookbackSeconds);
  }
}
