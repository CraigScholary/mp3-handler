package com.scholary.mp3.handler.transcript;

import com.scholary.mp3.handler.strategy.ChunkPlan;
import com.scholary.mp3.handler.strategy.MergeStrategy;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Simple concatenation merger for silence-aware chunking.
 *
 * <p>When chunks are split at silence boundaries, there's no overlap to handle. We simply
 * concatenate all segments in order, adjusting timestamps by each chunk's offset.
 *
 * <p>This is much simpler than word matching because:
 *
 * <ul>
 *   <li>No overlaps to resolve
 *   <li>No duplicate detection needed
 *   <li>Clean cuts at silence points
 * </ul>
 */
@Component
public class ConcatenationMerger implements MergeStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConcatenationMerger.class);

  @Override
  public List<MergedSegment> merge(List<ChunkTranscript> chunkTranscripts, List<ChunkPlan> chunkPlans) {
    // Validate no overlaps (for debugging)
    validateNoOverlaps(chunkTranscripts);
    return mergeByConcat(chunkTranscripts);
  }

  @Override
  public String getStrategyName() {
    return "CONCATENATION";
  }

  /**
   * Merge chunk transcripts by simple concatenation.
   *
   * <p>Assumes chunks have no overlap (silence-aware mode).
   *
   * @param chunkTranscripts the transcripts to merge
   * @return merged transcript
   */
  private List<MergedSegment> mergeByConcat(List<ChunkTranscript> chunkTranscripts) {
    if (chunkTranscripts.isEmpty()) {
      return List.of();
    }

    LOGGER.info("Merging {} chunks with simple concatenation", chunkTranscripts.size());

    List<MergedSegment> merged = new ArrayList<>();
    int totalSegments = 0;

    for (ChunkTranscript chunk : chunkTranscripts) {
      LOGGER.debug(
          "Processing chunk {}: offset={}s, segments={}",
          chunk.chunkIndex(),
          chunk.chunkStartOffset(),
          chunk.segments().size());

      // Simply adjust timestamps and add all segments
      for (var segment : chunk.segments()) {
        double absoluteStart = chunk.chunkStartOffset() + segment.start();
        double absoluteEnd = chunk.chunkStartOffset() + segment.end();

        merged.add(new MergedSegment(absoluteStart, absoluteEnd, segment.text()));
        totalSegments++;
      }
    }

    LOGGER.info("Concatenation complete: {} total segments from {} chunks", totalSegments, chunkTranscripts.size());
    return merged;
  }

  /**
   * Validate that chunks have no overlaps (for debugging).
   *
   * <p>Logs warnings if chunks appear to overlap, which shouldn't happen in silence-aware mode.
   *
   * @param chunkTranscripts the transcripts to validate
   */
  public void validateNoOverlaps(List<ChunkTranscript> chunkTranscripts) {
    for (int i = 1; i < chunkTranscripts.size(); i++) {
      ChunkTranscript previous = chunkTranscripts.get(i - 1);
      ChunkTranscript current = chunkTranscripts.get(i);

      double previousEnd = previous.chunkStartOffset() + getChunkDuration(previous);
      double currentStart = current.chunkStartOffset();

      if (currentStart < previousEnd) {
        LOGGER.warn(
            "Unexpected overlap detected between chunks {} and {}: previous ends at {}s, current starts at {}s",
            i - 1,
            i,
            previousEnd,
            currentStart);
      }
    }
  }

  /**
   * Calculate the duration of a chunk based on its last segment.
   */
  private double getChunkDuration(ChunkTranscript chunk) {
    if (chunk.segments().isEmpty()) {
      return 0.0;
    }
    var lastSegment = chunk.segments().get(chunk.segments().size() - 1);
    return lastSegment.end();
  }
}
