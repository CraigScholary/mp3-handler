package com.scholary.mp3.handler.transcript;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Aggregates Whisper's fine-grained segments into larger chunks.
 *
 * <p>Whisper returns many small segments (typically 5-30 seconds) based on natural speech pauses.
 * This aggregator combines them into larger segments that align with the original audio chunks.
 *
 * <p>Strategy:
 * <ul>
 *   <li>Group segments by their chunk index
 *   <li>Combine all segments within each chunk into one large segment
 *   <li>Preserve timing information and text
 * </ul>
 */
@Component
public class SegmentAggregator {

  private static final Logger LOGGER = LoggerFactory.getLogger(SegmentAggregator.class);

  /**
   * Aggregate segments by chunk boundaries.
   *
   * <p>Each chunk's segments are combined into a single large segment.
   *
   * @param segments all merged segments from transcription
   * @param chunkBoundaries list of chunk start times (in seconds)
   * @return aggregated segments (one per chunk)
   */
  public List<AggregatedSegment> aggregateByChunks(
      List<MergedSegment> segments, List<Double> chunkBoundaries) {

    if (segments.isEmpty()) {
      return List.of();
    }

    LOGGER.info(
        "Aggregating {} segments into {} chunks", segments.size(), chunkBoundaries.size() + 1);

    List<AggregatedSegment> aggregated = new ArrayList<>();

    // Add implicit boundaries
    List<Double> allBoundaries = new ArrayList<>();
    allBoundaries.add(0.0); // Start
    allBoundaries.addAll(chunkBoundaries);
    allBoundaries.add(Double.MAX_VALUE); // End

    // Group segments by chunk
    for (int chunkIndex = 0; chunkIndex < allBoundaries.size() - 1; chunkIndex++) {
      double chunkStart = allBoundaries.get(chunkIndex);
      double chunkEnd = allBoundaries.get(chunkIndex + 1);

      // Find all segments in this chunk
      List<MergedSegment> chunkSegments =
          segments.stream()
              .filter(s -> s.start() >= chunkStart && s.start() < chunkEnd)
              .toList();

      if (!chunkSegments.isEmpty()) {
        // Combine all text
        StringBuilder combinedText = new StringBuilder();
        for (MergedSegment segment : chunkSegments) {
          if (combinedText.length() > 0) {
            combinedText.append(" ");
          }
          combinedText.append(segment.text());
        }

        // Use first segment's start and last segment's end
        double start = chunkSegments.get(0).start();
        double end = chunkSegments.get(chunkSegments.size() - 1).end();

        aggregated.add(
            new AggregatedSegment(
                chunkIndex, start, end, combinedText.toString(), chunkSegments.size()));

        LOGGER.debug(
            "Chunk {}: aggregated {} segments ({}s-{}s, {} chars)",
            chunkIndex,
            chunkSegments.size(),
            String.format("%.2f", start),
            String.format("%.2f", end),
            combinedText.length());
      }
    }

    LOGGER.info("Aggregation complete: {} chunks created", aggregated.size());
    return aggregated;
  }

  /**
   * Represents an aggregated segment (multiple Whisper segments combined).
   *
   * @param chunkIndex the chunk index this segment belongs to
   * @param start start time in seconds
   * @param end end time in seconds
   * @param text combined text from all segments
   * @param originalSegmentCount number of original Whisper segments combined
   */
  public record AggregatedSegment(
      int chunkIndex, double start, double end, String text, int originalSegmentCount) {

    public double duration() {
      return end - start;
    }
  }
}
