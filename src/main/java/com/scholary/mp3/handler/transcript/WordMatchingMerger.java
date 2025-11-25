package com.scholary.mp3.handler.transcript;

import com.scholary.mp3.handler.transcript.LongestMatchFinder.MatchResult;
import com.scholary.mp3.handler.whisper.TranscriptSegment;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Merges overlapping transcripts using word-level matching.
 */
@Component
public class WordMatchingMerger {

  private static final Logger LOGGER = LoggerFactory.getLogger(WordMatchingMerger.class);
  private final LongestMatchFinder matchFinder;

  public WordMatchingMerger(LongestMatchFinder matchFinder) {
    this.matchFinder = matchFinder;
  }

  /**
   * Merge two consecutive chunk transcripts.
   * Returns only the segments from currentChunk that don't overlap with prevChunk.
   * Uses timestamp-based deduplication to avoid repeating content.
   */
  public List<MergedSegment> merge(ChunkTranscript prevChunk, ChunkTranscript currentChunk) {
    // Find the last segment timestamp from prevChunk
    if (prevChunk.segments().isEmpty()) {
      return convertToMergedSegments(currentChunk);
    }
    
    // Get the absolute end time of the last segment in prevChunk
    TranscriptSegment lastPrevSegment = prevChunk.segments().get(prevChunk.segments().size() - 1);
    double prevChunkAbsoluteEnd = prevChunk.startTime() + lastPrevSegment.end();
    
    LOGGER.info(
        "Merging chunks: prevChunk ends at {}, currentChunk starts at {}",
        prevChunkAbsoluteEnd,
        currentChunk.startTime());
    
    // Only include segments from currentChunk that start AFTER prevChunk ended
    List<MergedSegment> result = new ArrayList<>();
    for (TranscriptSegment seg : currentChunk.segments()) {
      double absoluteStart = currentChunk.startTime() + seg.start();
      
      // Only include if this segment starts after the previous chunk ended
      if (absoluteStart >= prevChunkAbsoluteEnd) {
        result.add(new MergedSegment(
            absoluteStart,
            currentChunk.startTime() + seg.end(),
            seg.text()));
      }
    }
    
    LOGGER.info("Kept {} segments from currentChunk (skipped overlapping segments)", result.size());
    
    return result;
  }

  private List<MergedSegment> convertToMergedSegments(ChunkTranscript chunk) {
    return chunk.segments().stream()
        .map(s -> new MergedSegment(
            chunk.startTime() + s.start(),
            chunk.startTime() + s.end(),
            s.text()))
        .toList();
  }
}
