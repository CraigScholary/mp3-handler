package com.scholary.mp3.handler.strategy;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fixed-interval chunking with overlaps.
 *
 * <p>Splits audio into fixed-duration chunks (e.g., 1 hour) with configurable overlaps (e.g., 30
 * seconds). The overlaps ensure no words are lost at boundaries and enable word-level matching
 * during merge.
 *
 * <p>Example with 1-hour chunks and 30s overlap:
 *
 * <pre>
 * Chunk 0: 0:00 - 60:30 (includes 30s extra)
 * Chunk 1: 59:30 - 120:00 (starts 30s before previous end)
 * Chunk 2: 119:30 - 180:00
 * </pre>
 */
@Component
public class OverlapChunkingStrategy implements ChunkingStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(OverlapChunkingStrategy.class);

  @Override
  public List<ChunkPlan> planChunks(ChunkingContext context) {
    double totalDuration = context.estimatedDuration();
    double chunkDuration = context.config().maxChunkDurationSeconds();
    double overlap = context.config().overlapSeconds() != null ? context.config().overlapSeconds() : 0.0;

    LOGGER.info(
        "Planning overlap-based chunks: totalDuration={}s, chunkDuration={}s, overlap={}s",
        totalDuration,
        chunkDuration,
        overlap);

    List<ChunkPlan> plans = new ArrayList<>();
    double currentStart = 0.0;
    int index = 0;

    while (currentStart < totalDuration) {
      // Calculate chunk end (don't exceed total duration)
      double chunkEnd = Math.min(currentStart + chunkDuration, totalDuration);

      // Add overlap to the end (except for last chunk)
      double actualEnd = chunkEnd;
      if (chunkEnd < totalDuration) {
        actualEnd = Math.min(chunkEnd + overlap, totalDuration);
      }

      // Calculate overlap region for this chunk
      Double overlapStart = null;
      Double overlapEnd = null;
      if (index > 0) {
        // This chunk has overlap at the beginning
        overlapStart = currentStart;
        overlapEnd = Math.min(currentStart + overlap, actualEnd);
      }

      plans.add(
          new ChunkPlan(index, currentStart, actualEnd, index > 0, overlapStart, overlapEnd));

      LOGGER.debug(
          "Chunk {}: {}s - {}s (duration: {}s, overlap: {}s)",
          index,
          currentStart,
          actualEnd,
          actualEnd - currentStart,
          overlapStart != null ? overlapEnd - overlapStart : 0.0);

      // Next chunk starts (current end - overlap)
      currentStart = chunkEnd;
      index++;
    }

    LOGGER.info("Planned {} chunks with overlap", plans.size());
    return plans;
  }

  @Override
  public String getStrategyName() {
    return "OVERLAP";
  }
}
