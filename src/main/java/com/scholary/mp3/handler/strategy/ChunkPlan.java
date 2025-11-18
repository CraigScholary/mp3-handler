package com.scholary.mp3.handler.strategy;

/**
 * Plan for a single audio chunk.
 *
 * <p>Contains all information needed to download and process a chunk.
 */
public record ChunkPlan(
    int chunkIndex,
    double startSeconds,
    double endSeconds,
    boolean hasOverlap,
    Double overlapStartSeconds, // null if no overlap
    Double overlapEndSeconds // null if no overlap
    ) {

  /**
   * Create a chunk plan without overlap.
   */
  public ChunkPlan(int chunkIndex, double startSeconds, double endSeconds) {
    this(chunkIndex, startSeconds, endSeconds, false, null, null);
  }

  /**
   * Get the duration of this chunk in seconds.
   */
  public double duration() {
    return endSeconds - startSeconds;
  }

  /**
   * Get the overlap duration in seconds (0 if no overlap).
   */
  public double overlapDuration() {
    if (!hasOverlap || overlapStartSeconds == null || overlapEndSeconds == null) {
      return 0.0;
    }
    return overlapEndSeconds - overlapStartSeconds;
  }
}
