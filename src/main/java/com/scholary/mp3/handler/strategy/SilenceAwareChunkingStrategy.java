package com.scholary.mp3.handler.strategy;

import com.scholary.mp3.handler.chunking.BreakpointWithSilence;
import com.scholary.mp3.handler.chunking.StreamingSilenceAnalyzer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Silence-aware chunking strategy.
 *
 * <p>Analyzes the audio file to detect silence intervals and splits at natural pauses. This
 * produces variable-length chunks (up to max duration) that align with content structure.
 *
 * <p>No overlaps are used because chunks are split at silence points where no speech occurs.
 *
 * <p>Example:
 *
 * <pre>
 * Chunk 0: 0:00 - 52:15 (silence detected at 52:15)
 * Chunk 1: 52:15 - 105:40 (silence detected at 105:40)
 * Chunk 2: 105:40 - 158:20 (silence detected at 158:20)
 * </pre>
 */
@Component
public class SilenceAwareChunkingStrategy implements ChunkingStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(SilenceAwareChunkingStrategy.class);

  private final StreamingSilenceAnalyzer silenceAnalyzer;

  public SilenceAwareChunkingStrategy(StreamingSilenceAnalyzer silenceAnalyzer) {
    this.silenceAnalyzer = silenceAnalyzer;
  }

  @Override
  public List<ChunkPlan> planChunks(ChunkingContext context) throws IOException {
    LOGGER.info(
        "Planning silence-aware chunks: totalDuration={}s, maxChunkDuration={}s",
        context.estimatedDuration(),
        context.config().maxChunkDurationSeconds());

    // Find silence breakpoints
    List<BreakpointWithSilence> breakpoints =
        silenceAnalyzer.findBreakpointsGreedyWithSilence(
            context.bucket(),
            context.key(),
            context.fileSize(),
            context.config().maxChunkDurationSeconds());

    LOGGER.info("Found {} silence breakpoints", breakpoints.size());

    // Convert breakpoints to chunk plans
    List<ChunkPlan> plans = new ArrayList<>();
    double previousBreakpoint = 0.0;

    for (int i = 0; i < breakpoints.size(); i++) {
      BreakpointWithSilence bp = breakpoints.get(i);

      plans.add(new ChunkPlan(i, previousBreakpoint, bp.breakpoint()));

      LOGGER.debug(
          "Chunk {}: {}s - {}s (duration: {}s, at silence: {})",
          i,
          previousBreakpoint,
          bp.breakpoint(),
          bp.breakpoint() - previousBreakpoint,
          bp.hasSilence());

      previousBreakpoint = bp.breakpoint();
    }

    // Add final chunk
    if (previousBreakpoint < context.estimatedDuration()) {
      plans.add(
          new ChunkPlan(
              plans.size(), previousBreakpoint, context.estimatedDuration()));

      LOGGER.debug(
          "Chunk {} (final): {}s - {}s (duration: {}s)",
          plans.size() - 1,
          previousBreakpoint,
          context.estimatedDuration(),
          context.estimatedDuration() - previousBreakpoint);
    }

    LOGGER.info("Planned {} chunks with silence-aware splitting", plans.size());
    return plans;
  }

  @Override
  public String getStrategyName() {
    return "SILENCE_AWARE";
  }
}
