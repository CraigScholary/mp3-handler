package com.scholary.mp3.handler.transcript;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the longest exact word sequence match between two text segments.
 *
 * <p>This is used for merging overlapping transcripts. The algorithm assumes the first text
 * (end of previous chunk) is more accurate due to having more context, so we search for where
 * it appears in the second text (start of next chunk).
 *
 * <p>Uses a sliding window approach to find the longest contiguous sequence of matching words.
 */
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
    int bestText1End = 0;
    int bestText2Start = 0;

    // Try each possible starting position in text2
    for (int text2Start = 0; text2Start < text2Words.size(); text2Start++) {

      // Try each possible ending position in text1
      for (int text1End = text1Words.size(); text1End > 0; text1End--) {

        // Calculate how many words we're trying to match
        int text1Length = text1End;
        int text2Remaining = text2Words.size() - text2Start;
        int maxPossibleMatch = Math.min(text1Length, text2Remaining);

        // Skip if we can't beat the current best
        if (maxPossibleMatch <= bestMatchLength) {
          continue;
        }

        // Try to match from the end of text1 to the current position in text2
        int matchLength = 0;
        for (int i = 0; i < maxPossibleMatch; i++) {
          int text1Index = text1End - maxPossibleMatch + i;
          int text2Index = text2Start + i;

          if (wordsMatch(text1Words.get(text1Index), text2Words.get(text2Index))) {
            matchLength++;
          } else {
            break; // Must be consecutive
          }
        }

        // Update best match if this is better
        if (matchLength > bestMatchLength) {
          bestMatchLength = matchLength;
          bestText1End = text1End;
          bestText2Start = text2Start;
        }
      }
    }

    // Only return match if it meets minimum length
    if (bestMatchLength >= minMatchLength) {
      List<String> matchedWords =
          text1Words.subList(bestText1End - bestMatchLength, bestText1End);
      return new MatchResult(bestMatchLength, bestText1End, bestText2Start, matchedWords);
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
