package com.scholary.mp3.handler.transcript;

import com.scholary.mp3.handler.strategy.ChunkPlan;
import com.scholary.mp3.handler.strategy.MergeStrategy;
import com.scholary.mp3.handler.transcript.LongestMatchFinder.MatchResult;
import com.scholary.mp3.handler.whisper.TranscriptSegment;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Merges overlapping transcripts using word-level matching.
 *
 * <p>Strategy: The end of the previous chunk is more accurate (has more context), so we find where
 * it appears in the next chunk and use that as the merge point.
 *
 * <p>Algorithm:
 *
 * <ol>
 *   <li>Extract last 30s of Chunk N
 *   <li>Extract first 30s of Chunk N+1
 *   <li>Find longest exact word sequence match
 *   <li>Keep Chunk N up to the match point
 *   <li>Keep Chunk N+1 from the match point onward
 *   <li>Discard the duplicate portion
 * </ol>
 */
@Component
public class WordMatchingMerger implements MergeStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(WordMatchingMerger.class);

  // Overlap duration in seconds (configurable)
  private static final double OVERLAP_SECONDS = 30.0;

  // Minimum number of consecutive words to consider a valid match
  private static final int MIN_MATCH_LENGTH = 3;

  @Override
  public List<MergedSegment> merge(List<ChunkTranscript> chunkTranscripts, List<ChunkPlan> chunkPlans) {
    // For now, ignore chunkPlans - we use fixed 30s overlap
    return merge(chunkTranscripts);
  }

  /**
   * Merge chunk transcripts with word-level matching in overlap regions.
   *
   * <p>Convenience method for tests that don't need ChunkPlan information.
   *
   * @param chunkTranscripts the transcripts to merge
   * @return merged transcript with duplicates removed
   */
  public List<MergedSegment> merge(List<ChunkTranscript> chunkTranscripts) {
    return mergeWithFixedOverlap(chunkTranscripts);
  }

  @Override
  public String getStrategyName() {
    return "WORD_MATCHING";
  }

  /**
   * Merge chunk transcripts with word-level matching in overlap regions.
   *
   * @param chunkTranscripts the transcripts to merge
   * @return merged transcript with duplicates removed
   */
  private List<MergedSegment> mergeWithFixedOverlap(List<ChunkTranscript> chunkTranscripts) {
    if (chunkTranscripts.isEmpty()) {
      return List.of();
    }

    if (chunkTranscripts.size() == 1) {
      // Single chunk - just convert to merged segments
      return convertToMergedSegments(chunkTranscripts.get(0));
    }

    LOGGER.info("Merging {} chunks with word-level matching", chunkTranscripts.size());

    List<MergedSegment> merged = new ArrayList<>();

    // Process first chunk completely
    ChunkTranscript firstChunk = chunkTranscripts.get(0);
    merged.addAll(convertToMergedSegments(firstChunk));

    // Process remaining chunks with overlap handling
    for (int i = 1; i < chunkTranscripts.size(); i++) {
      ChunkTranscript previousChunk = chunkTranscripts.get(i - 1);
      ChunkTranscript currentChunk = chunkTranscripts.get(i);

      LOGGER.info(
          "Merging chunk {} (offset: {}s) with chunk {} (offset: {}s)",
          i - 1,
          previousChunk.chunkStartOffset(),
          i,
          currentChunk.chunkStartOffset());

      // Extract overlap regions
      List<TranscriptSegment> previousOverlap =
          extractOverlapEnd(previousChunk, OVERLAP_SECONDS);
      List<TranscriptSegment> currentOverlap = extractOverlapStart(currentChunk, OVERLAP_SECONDS);

      // Find longest match
      MatchResult match = findMatch(previousOverlap, currentOverlap);

      if (match.hasMatch()) {
        LOGGER.info(
            "Found match: {} words at previous[{}] -> current[{}]: \"{}\"",
            match.matchLength(),
            match.text1EndIndex(),
            match.text2StartIndex(),
            String.join(" ", match.matchedWords()));

        // Remove the duplicate portion from merged (end of previous chunk)
        int segmentsToRemove = countSegmentsInOverlap(previousOverlap, match.text1EndIndex());
        for (int j = 0; j < segmentsToRemove; j++) {
          merged.remove(merged.size() - 1);
        }

        // Add current chunk from the match point onward
        List<MergedSegment> currentSegments =
            convertToMergedSegmentsFromMatch(currentChunk, currentOverlap, match);
        merged.addAll(currentSegments);

      } else {
        LOGGER.warn(
            "No word match found between chunks {} and {} - using simple concatenation",
            i - 1,
            i);

        // Fallback: simple concatenation (no overlap removal)
        merged.addAll(convertToMergedSegments(currentChunk));
      }
    }

    LOGGER.info("Merge complete: {} total segments", merged.size());
    return merged;
  }

  /**
   * Extract the last N seconds of segments from a chunk.
   */
  private List<TranscriptSegment> extractOverlapEnd(
      ChunkTranscript chunk, double overlapSeconds) {
    List<TranscriptSegment> overlap = new ArrayList<>();

    // Calculate the time threshold
    double chunkDuration = getChunkDuration(chunk);
    double overlapStartTime = chunkDuration - overlapSeconds;

    for (TranscriptSegment segment : chunk.segments()) {
      // Include segments that start in the overlap region
      if (segment.start() >= overlapStartTime) {
        overlap.add(segment);
      }
    }

    return overlap;
  }

  /**
   * Extract the first N seconds of segments from a chunk.
   */
  private List<TranscriptSegment> extractOverlapStart(
      ChunkTranscript chunk, double overlapSeconds) {
    List<TranscriptSegment> overlap = new ArrayList<>();

    for (TranscriptSegment segment : chunk.segments()) {
      // Include segments that start in the overlap region
      if (segment.start() <= overlapSeconds) {
        overlap.add(segment);
      } else {
        break; // Segments are ordered by time
      }
    }

    return overlap;
  }

  /**
   * Find the longest word match between overlap regions.
   */
  private MatchResult findMatch(
      List<TranscriptSegment> previousOverlap, List<TranscriptSegment> currentOverlap) {

    List<String> previousWords = LongestMatchFinder.extractWords(previousOverlap);
    List<String> currentWords = LongestMatchFinder.extractWords(currentOverlap);

    LOGGER.debug(
        "Searching for match: previous={} words, current={} words",
        previousWords.size(),
        currentWords.size());

    return LongestMatchFinder.findLongestMatch(previousWords, currentWords, MIN_MATCH_LENGTH);
  }

  /**
   * Count how many segments contain the matched words.
   *
   * <p>We need to remove these segments from the merged list since they're duplicates.
   */
  private int countSegmentsInOverlap(List<TranscriptSegment> overlapSegments, int wordEndIndex) {
    int wordCount = 0;
    int segmentCount = 0;

    for (TranscriptSegment segment : overlapSegments) {
      String[] words = segment.text().split("\\s+");
      wordCount += words.length;
      segmentCount++;

      if (wordCount >= wordEndIndex) {
        break;
      }
    }

    return segmentCount;
  }

  /**
   * Convert chunk segments to merged segments, starting from the match point.
   */
  private List<MergedSegment> convertToMergedSegmentsFromMatch(
      ChunkTranscript chunk, List<TranscriptSegment> overlapSegments, MatchResult match) {

    List<MergedSegment> merged = new ArrayList<>();

    // Calculate which segment contains the match point
    int wordCount = 0;
    int matchSegmentIndex = 0;
    int wordOffsetInSegment = 0;

    for (int i = 0; i < overlapSegments.size(); i++) {
      TranscriptSegment segment = overlapSegments.get(i);
      String[] words = segment.text().split("\\s+");

      if (wordCount + words.length > match.text2StartIndex()) {
        matchSegmentIndex = i;
        wordOffsetInSegment = match.text2StartIndex() - wordCount;
        break;
      }

      wordCount += words.length;
    }

    // Find the corresponding segment in the full chunk
    TranscriptSegment matchSegment = overlapSegments.get(matchSegmentIndex);
    int fullChunkSegmentIndex = chunk.segments().indexOf(matchSegment);

    // Add segments from the match point onward
    for (int i = fullChunkSegmentIndex; i < chunk.segments().size(); i++) {
      TranscriptSegment segment = chunk.segments().get(i);

      // For the first segment, we might need to trim the beginning
      if (i == fullChunkSegmentIndex && wordOffsetInSegment > 0) {
        String[] words = segment.text().split("\\s+");
        String trimmedText = String.join(" ", java.util.Arrays.copyOfRange(words, wordOffsetInSegment, words.length));
        
        merged.add(
            new MergedSegment(
                chunk.chunkStartOffset() + segment.start(),
                chunk.chunkStartOffset() + segment.end(),
                trimmedText));
      } else {
        merged.add(
            new MergedSegment(
                chunk.chunkStartOffset() + segment.start(),
                chunk.chunkStartOffset() + segment.end(),
                segment.text()));
      }
    }

    return merged;
  }

  /**
   * Convert all segments from a chunk to merged segments.
   */
  private List<MergedSegment> convertToMergedSegments(ChunkTranscript chunk) {
    List<MergedSegment> merged = new ArrayList<>();

    for (TranscriptSegment segment : chunk.segments()) {
      merged.add(
          new MergedSegment(
              chunk.chunkStartOffset() + segment.start(),
              chunk.chunkStartOffset() + segment.end(),
              segment.text()));
    }

    return merged;
  }

  /**
   * Calculate the duration of a chunk based on its last segment.
   */
  private double getChunkDuration(ChunkTranscript chunk) {
    if (chunk.segments().isEmpty()) {
      return 0.0;
    }
    TranscriptSegment lastSegment = chunk.segments().get(chunk.segments().size() - 1);
    return lastSegment.end();
  }
}
