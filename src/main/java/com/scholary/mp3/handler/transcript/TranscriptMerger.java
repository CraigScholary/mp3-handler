package com.scholary.mp3.handler.transcript;

import com.scholary.mp3.handler.whisper.TranscriptSegment;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Simple transcript merger (deprecated - use WordMatchingMerger instead).
 */
@Component
public class TranscriptMerger {

  private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptMerger.class);

  // Similarity threshold for detecting duplicate segments
  // If two segments have similar text and timing, we consider them duplicates
  private static final double TIME_TOLERANCE = 1.0; // seconds
  private static final double TEXT_SIMILARITY_THRESHOLD = 0.7;

  /**
   * Merge multiple chunk transcripts into a single transcript.
   *
   * @param chunkTranscripts the transcripts to merge, in order
   * @return a list of merged segments with absolute timing
   */
  public List<MergedSegment> merge(List<ChunkTranscript> chunkTranscripts) {
    LOGGER.info("Merging {} chunk transcripts", chunkTranscripts.size());

    List<MergedSegment> merged = new ArrayList<>();
    double lastEndTime = 0.0;
    int previousChunkIndex = -1;

    for (ChunkTranscript chunk : chunkTranscripts) {
      LOGGER.debug(
          "Processing chunk {}: offset={}s, segments={}",
          chunk.chunkIndex(),
          chunk.startTime(),
          chunk.segments().size());

      int duplicatesRemoved = 0;
      int charsRemoved = 0;

      for (TranscriptSegment segment : chunk.segments()) {
        // Adjust timestamps to absolute time in the original file
        double absoluteStart = chunk.startTime() + segment.start();
        double absoluteEnd = chunk.startTime() + segment.end();

        // Skip segments that are clearly in the overlap region and likely duplicates
        // We keep segments from the earlier chunk and skip similar ones from later chunks
        if (absoluteStart < lastEndTime) {
          // This segment overlaps with previous content
          // Check if it's a duplicate
          if (isDuplicate(merged, absoluteStart, absoluteEnd, segment.text())) {
            LOGGER.debug(
                "Skipping duplicate segment at {}s-{}s: '{}'",
                absoluteStart,
                absoluteEnd,
                truncate(segment.text(), 50));
            duplicatesRemoved++;
            charsRemoved += segment.text().length();
            continue;
          }
        }

        // Add the segment
        MergedSegment mergedSegment = new MergedSegment(absoluteStart, absoluteEnd, segment.text());
        merged.add(mergedSegment);

        // Update last end time
        lastEndTime = Math.max(lastEndTime, absoluteEnd);
      }

      // Log overlap merge if this isn't the first chunk
      if (previousChunkIndex >= 0) {
        double overlapSeconds = lastEndTime - chunk.startTime();
        LOGGER.debug(
            "Merged overlap between chunks {} and {}: {}s overlap, {} duplicates removed",
            previousChunkIndex,
            chunk.chunkIndex(),
            overlapSeconds,
            duplicatesRemoved);
      }
      previousChunkIndex = chunk.chunkIndex();
    }

    LOGGER.info("Merged transcript contains {} segments", merged.size());
    return merged;
  }

  /**
   * Check if a segment is a duplicate of something already in the merged list.
   *
   * <p>We look at the last few segments (within the overlap window) and check for similar timing
   * and text content.
   */
  private boolean isDuplicate(List<MergedSegment> merged, double start, double end, String text) {
    // Look at recent segments (last 10 or so, which should cover the overlap)
    int lookbackStart = Math.max(0, merged.size() - 10);

    for (int i = lookbackStart; i < merged.size(); i++) {
      MergedSegment existing = merged.get(i);

      // Check if timing is similar
      boolean timingSimilar =
          Math.abs(existing.start() - start) < TIME_TOLERANCE
              && Math.abs(existing.end() - end) < TIME_TOLERANCE;

      if (timingSimilar) {
        // Check if text is similar
        double similarity = textSimilarity(existing.text(), text);
        if (similarity >= TEXT_SIMILARITY_THRESHOLD) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Calculate text similarity using a simple metric.
   *
   * <p>We use Jaccard similarity on words: intersection / union. This is fast and good enough for
   * detecting near-duplicates.
   *
   * <p>More sophisticated approaches (edit distance, embeddings) would be overkill here since we're
   * just trying to catch obvious duplicates from the overlap.
   */
  private double textSimilarity(String text1, String text2) {
    String[] words1 = text1.toLowerCase().split("\\s+");
    String[] words2 = text2.toLowerCase().split("\\s+");

    // Count common words
    int common = 0;
    for (String word1 : words1) {
      for (String word2 : words2) {
        if (word1.equals(word2)) {
          common++;
          break;
        }
      }
    }

    // Jaccard similarity: intersection / union
    int union = words1.length + words2.length - common;
    return union > 0 ? (double) common / union : 0.0;
  }

  /** Truncate text for logging. */
  private String truncate(String text, int maxLength) {
    if (text.length() <= maxLength) {
      return text;
    }
    return text.substring(0, maxLength) + "...";
  }
}
