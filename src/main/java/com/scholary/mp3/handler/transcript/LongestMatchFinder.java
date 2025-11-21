package com.scholary.mp3.handler.transcript;

import com.scholary.mp3.handler.whisper.TranscriptSegment;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Finds the longest exact word sequence match between two text segments.
 *
 * <p>This is used for merging overlapping transcripts. The algorithm assumes the first text
 * (end of previous chunk) is more accurate due to having more context, so we search for where
 * it appears in the second text (start of next chunk).
 *
 * <p>Uses a sliding window approach to find the longest contiguous sequence of matching words.
 */
@Component
public class LongestMatchFinder {

  /**
   * Result of finding the longest match between two texts.
   *
   * @param matchLength number of consecutive words that matched
   * @param text1EndIndex index in text1 where the match ends (exclusive)
   * @param text2StartIndex index in text2 where the match starts (inclusive)
   * @param matchedWords the actual words that matched
   */
  public record MatchResult(
      int matchLength, int text1EndIndex, int text2StartIndex, List<String> matchedWords) {

    public boolean hasMatch() {
      return matchLength > 0;
    }
  }

  /**
   * Find the longest exact word sequence match between two texts.
   *
   * <p>Algorithm: For each possible starting position in text2, try to match as many consecutive
   * words as possible from the end of text1. Keep track of the longest match found.
   *
   * <p>Example: text1 = ["the", "quick", "brown", "fox"] text2 = ["brown", "fox", "jumps",
   * "over"] Result: matchLength=2, text1EndIndex=4, text2StartIndex=0, matchedWords=["brown",
   * "fox"]
   *
   * @param text1Words words from the first text (end of previous chunk)
   * @param text2Words words from the second text (start of next chunk)
   * @param minMatchLength minimum number of words to consider a valid match (default: 3)
   * @return the longest match found, or empty match if none found
   */
  public static MatchResult findLongestMatch(
      List<String> text1Words, List<String> text2Words, int minMatchLength) {

    if (text1Words.isEmpty() || text2Words.isEmpty()) {
      return new MatchResult(0, 0, 0, List.of());
    }

    int bestMatchLength = 0;
    int bestText1Start = 0;
    int bestText2Start = 0;

    // Try each possible starting position in text1
    for (int text1Start = 0; text1Start < text1Words.size(); text1Start++) {
      // Try each possible starting position in text2
      for (int text2Start = 0; text2Start < text2Words.size(); text2Start++) {
        
        // Count consecutive matches from these starting positions
        int matchLength = 0;
        while (text1Start + matchLength < text1Words.size() &&
               text2Start + matchLength < text2Words.size() &&
               wordsMatch(text1Words.get(text1Start + matchLength), 
                         text2Words.get(text2Start + matchLength))) {
          matchLength++;
        }

        // Update best match if this is better
        if (matchLength > bestMatchLength) {
          bestMatchLength = matchLength;
          bestText1Start = text1Start;
          bestText2Start = text2Start;
        }
      }
    }

    // Only return match if it meets minimum length
    if (bestMatchLength >= minMatchLength) {
      List<String> matchedWords =
          text1Words.subList(bestText1Start, bestText1Start + bestMatchLength);
      return new MatchResult(bestMatchLength, bestText1Start + bestMatchLength, bestText2Start, matchedWords);
    }

    return new MatchResult(0, 0, 0, List.of());
  }

  /**
   * Overload with default minimum match length of 3 words.
   */
  public static MatchResult findLongestMatch(List<String> text1Words, List<String> text2Words) {
    return findLongestMatch(text1Words, text2Words, 3);
  }

  /**
   * Check if two words match.
   *
   * <p>Uses case-insensitive comparison and strips common punctuation. This handles minor
   * variations in Whisper's output (e.g., "hello" vs "Hello" or "world" vs "world.").
   *
   * @param word1 first word
   * @param word2 second word
   * @return true if words match
   */
  private static boolean wordsMatch(String word1, String word2) {
    String normalized1 = normalizeWord(word1);
    String normalized2 = normalizeWord(word2);
    return normalized1.equals(normalized2);
  }

  /**
   * Normalize a word for comparison.
   *
   * <p>Converts to lowercase and removes common punctuation.
   */
  private static String normalizeWord(String word) {
    return word.toLowerCase()
        .replaceAll("[.,!?;:'\"]", "") // Remove punctuation
        .trim();
  }

  /**
   * Extract words from a list of transcript segments.
   *
   * @param segments the transcript segments
   * @return list of words in order
   */
  public static List<String> extractWords(List<TranscriptSegment> segments) {
    List<String> words = new ArrayList<>();
    for (TranscriptSegment segment : segments) {
      String[] segmentWords = segment.text().split("\\s+");
      for (String word : segmentWords) {
        if (!word.isEmpty()) {
          words.add(word);
        }
      }
    }
    return words;
  }

  /**
   * Extract words from text.
   *
   * @param text the text to extract words from
   * @return list of words
   */
  public static List<String> extractWords(String text) {
    List<String> words = new ArrayList<>();
    String[] textWords = text.split("\\s+");
    for (String word : textWords) {
      if (!word.isEmpty()) {
        words.add(word);
      }
    }
    return words;
  }
}
