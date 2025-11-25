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
   * 
   * <p>Strategy:
   * 1. Use word matching to find where prevChunk content appears in currentChunk
   * 2. Find the timestamp where the match ends in currentChunk
   * 3. Only keep segments from currentChunk that start AFTER the match point
   * 
   * <p>This ensures we don't lose content at boundaries while avoiding duplicates.
   */
  public List<MergedSegment> merge(ChunkTranscript prevChunk, ChunkTranscript currentChunk) {
    if (prevChunk.segments().isEmpty()) {
      return convertToMergedSegments(currentChunk);
    }
    
    if (currentChunk.segments().isEmpty()) {
      return List.of();
    }
    
    // Get overlap region: last N seconds of prevChunk and first N seconds of currentChunk
    double overlapStart = currentChunk.startTime();
    List<TranscriptSegment> prevOverlap = getSegmentsAfter(prevChunk.segments(), overlapStart - prevChunk.startTime());
    List<TranscriptSegment> currentOverlap = currentChunk.segments();
    
    if (prevOverlap.isEmpty()) {
      // No overlap, just concatenate
      LOGGER.info("No overlap between chunks, concatenating");
      return convertToMergedSegments(currentChunk);
    }
    
    // Extract words from overlap regions
    List<String> prevWords = extractWords(prevOverlap);
    List<String> currentWords = extractWords(currentOverlap);
    
    // Find longest word match
    MatchResult match = matchFinder.findLongestMatch(prevWords, currentWords);
    
    if (!match.hasMatch()) {
      LOGGER.warn("No word match found in overlap, using timestamp-based merge");
      // Fall back to timestamp-based: keep segments after prevChunk ends
      TranscriptSegment lastPrevSegment = prevChunk.segments().get(prevChunk.segments().size() - 1);
      double cutoffTime = prevChunk.startTime() + lastPrevSegment.end();
      return getSegmentsAfterTimestamp(currentChunk, cutoffTime);
    }
    
    LOGGER.info(
        "Found match: {} words at position {} in currentChunk",
        match.matchLength(),
        match.text2StartIndex());
    
    // Find the timestamp where the match ENDS in currentChunk
    // We want to keep everything AFTER the matched region
    int matchEndWordIndex = match.text2StartIndex() + match.matchLength();
    double cutoffTime = findTimestampAfterWord(currentChunk.segments(), matchEndWordIndex);
    
    LOGGER.info("Cutoff time: {}, keeping segments after this point", cutoffTime);
    
    return getSegmentsAfterTimestamp(currentChunk, cutoffTime);
  }
  
  /**
   * Get segments from chunk that start after the given absolute timestamp.
   */
  private List<MergedSegment> getSegmentsAfterTimestamp(ChunkTranscript chunk, double absoluteTime) {
    List<MergedSegment> result = new ArrayList<>();
    for (TranscriptSegment seg : chunk.segments()) {
      double absoluteStart = chunk.startTime() + seg.start();
      if (absoluteStart >= absoluteTime) {
        result.add(new MergedSegment(
            absoluteStart,
            chunk.startTime() + seg.end(),
            seg.text()));
      }
    }
    LOGGER.info("Kept {} segments after timestamp {}", result.size(), absoluteTime);
    return result;
  }
  
  /**
   * Find the absolute timestamp after the Nth word in the segments.
   */
  private double findTimestampAfterWord(List<TranscriptSegment> segments, int wordIndex) {
    int wordCount = 0;
    for (TranscriptSegment seg : segments) {
      String[] words = seg.text().trim().split("\\s+");
      wordCount += words.length;
      if (wordCount >= wordIndex) {
        // Return the END time of this segment (where the matched words end)
        return seg.end();
      }
    }
    // If we didn't find it, return the end of the last segment
    return segments.isEmpty() ? 0 : segments.get(segments.size() - 1).end();
  }
  
  private List<TranscriptSegment> getSegmentsAfter(List<TranscriptSegment> segments, double relativeTime) {
    return segments.stream()
        .filter(s -> s.start() >= relativeTime)
        .toList();
  }
  
  private List<String> extractWords(List<TranscriptSegment> segments) {
    List<String> words = new ArrayList<>();
    for (TranscriptSegment seg : segments) {
      String[] segWords = seg.text().trim().split("\\s+");
      for (String word : segWords) {
        if (!word.isEmpty()) {
          words.add(word);
        }
      }
    }
    return words;
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
