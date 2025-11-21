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
   */
  public List<MergedSegment> merge(ChunkTranscript prevChunk, ChunkTranscript currentChunk) {
    // Get overlap region from both chunks
    double overlapStart = currentChunk.startTime();
    
    List<TranscriptSegment> prevOverlap = getSegmentsAfter(prevChunk.segments(), overlapStart);
    List<TranscriptSegment> currentOverlap = currentChunk.segments();

    if (prevOverlap.isEmpty() || currentOverlap.isEmpty()) {
      LOGGER.warn("No overlap segments found, using simple concatenation");
      return convertToMergedSegments(currentChunk);
    }

    // Find longest word match
    MatchResult match = matchFinder.findLongestMatch(
        extractWords(prevOverlap),
        extractWords(currentOverlap));

    if (!match.hasMatch()) {
      LOGGER.warn("No word match found, using simple concatenation");
      return convertToMergedSegments(currentChunk);
    }

    LOGGER.info(
        "Found match: {} words at position {}",
        match.matchLength(),
        match.text2StartIndex());

    // Skip segments in currentChunk up to the match point
    int skipSegments = countSegmentsUpToWord(currentOverlap, match.text2StartIndex());
    
    List<MergedSegment> result = new ArrayList<>();
    for (int i = skipSegments; i < currentChunk.segments().size(); i++) {
      TranscriptSegment seg = currentChunk.segments().get(i);
      result.add(new MergedSegment(
          currentChunk.startTime() + seg.start(),
          currentChunk.startTime() + seg.end(),
          seg.text()));
    }

    return result;
  }

  private List<TranscriptSegment> getSegmentsAfter(List<TranscriptSegment> segments, double time) {
    return segments.stream()
        .filter(s -> s.start() >= time)
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

  private int countSegmentsUpToWord(List<TranscriptSegment> segments, int wordIndex) {
    int wordCount = 0;
    for (int i = 0; i < segments.size(); i++) {
      String[] words = segments.get(i).text().trim().split("\\s+");
      wordCount += words.length;
      if (wordCount > wordIndex) {
        return i;
      }
    }
    return segments.size();
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
