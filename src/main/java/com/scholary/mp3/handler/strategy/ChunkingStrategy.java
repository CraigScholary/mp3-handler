package com.scholary.mp3.handler.strategy;

import java.io.IOException;
import java.util.List;

/**
 * Strategy interface for chunking audio files.
 *
 * <p>Different strategies can be used based on the audio content and requirements:
 *
 * <ul>
 *   <li>Overlap-based: Fixed intervals with overlaps for word matching
 *   <li>Silence-aware: Dynamic chunks at natural pauses
 * </ul>
 */
public interface ChunkingStrategy {

  /**
   * Plan how to split the audio file into chunks.
   *
   * @param context the chunking context with file metadata and configuration
   * @return list of chunk plans
   * @throws IOException if planning fails
   */
  List<ChunkPlan> planChunks(ChunkingContext context) throws IOException;

  /**
   * Get the strategy name for logging and debugging.
   *
   * @return strategy name
   */
  String getStrategyName();
}
