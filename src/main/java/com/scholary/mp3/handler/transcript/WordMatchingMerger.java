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
   * <p>Strategy (prevChunk is more accurate because it has full context):
   * 1. Extract last N seconds of prevChunk (the overlap region we extended)
   * 2. Find where this content appears at the START of currentChunk
   * 3. Skip the matched portion in currentChunk (it's duplicate, less accurate)
   * 4. Keep everything in currentChunk AFTER the match
   * 
   * <p>This ensures we keep the more accurate prevChunk version and don't lose content.
   */
  public List<MergedSegment> merge(ChunkTranscript prevChunk, ChunkTranscript currentChunk) {
    if (prevChunk.segments().isEmpty()) {
      return convertToMergedSegments(currentChunk);
    }
    
    if (currentChunk.segments().isEmpty()) {
      return List.of();
    }
    
    // Calculate overlap region: where currentChunk overlaps with prevChunk
    // currentChunk.startTime() tells us where the overlap begins
    double overlapStartInPrev = currentChunk.startTime() - prevChunk.startTime();
    
    // Get the overlapping segments from prevChunk (its ending)
    List<TranscriptSegment> prevOverlapSegments = getSegmentsAfter(prevChunk.segments(), overlapStartInPrev);
    
    if (prevOverlapSegments.isEmpty()) {
      // No overlap, chunks are sequential
      LOGGER.info("No overlap between chunks, concatenating all of currentChunk");
      return convertToMergedSegments(currentChunk);
    }
    
    // Extract words from the overlap regions
    List<String> prevOverlapWords = extractWords(prevOverlapSegments);
    List<String> currentWords = extractWords(currentChunk.segments());
    
    LOGGER.info(
        "Overlap region: {} words from prevChunk, {} words from currentChunk",
        prevOverlapWords.size(),
        currentWords.size());
    
    // Find where prevChunk's ending appears in currentChunk's beginning
    MatchResult match = matchFinder.findLongestMatch(prevOverlapWords, currentWords);
    
    if (!match.hasMatch()) {
      LOGGER.warn("No word match found in overlap region, using timestamp fallback");
      // Fall back: skip segments in currentChunk that overlap with prevChunk's time range
      TranscriptSegment lastPrevSegment = prevChunk.segments().get(prevChunk.segments().size() - 1);
      double prevChunkAbsoluteEnd = prevChunk.startTime() + lastPrevSegment.end();
      return getSegmentsAfterTimestamp(currentChunk, prevChunkAbsoluteEnd);
    }
    
    LOGGER.info(
        "Found {} word match starting at position {} in currentChunk",
        match.matchLength(),
        match.text2StartIndex());
    
    // Find where the match ENDS in currentChunk (relative to chunk start)
    // We want to skip everything up to and including the match
    int matchEndWordIndex = match.text2StartIndex() + match.matchLength();
    double relativeSkipUntil = findTimestampAfterWord(currentChunk.segments(), matchEndWordIndex);
    double absoluteSkipUntil = currentChunk.startTime() + relativeSkipUntil;
    
    LOGGER.info(
        "Skipping currentChunk segments until {} (keeping segments after this)",
        absoluteSkipUntil);
    
    return getSegmentsAfterTimestamp(currentChunk, absoluteSkipUntil);
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
