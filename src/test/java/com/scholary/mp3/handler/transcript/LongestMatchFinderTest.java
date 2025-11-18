package com.scholary.mp3.handler.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import com.scholary.mp3.handler.transcript.LongestMatchFinder.MatchResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class LongestMatchFinderTest {

  @Test
  void findLongestMatch_perfectMatch() {
    List<String> text1 = List.of("the", "quick", "brown", "fox");
    List<String> text2 = List.of("brown", "fox", "jumps", "over");

    MatchResult result = LongestMatchFinder.findLongestMatch(text1, text2, 2);

    assertThat(result.hasMatch()).isTrue();
    assertThat(result.matchLength()).isEqualTo(2);
    assertThat(result.text1EndIndex()).isEqualTo(4);
    assertThat(result.text2StartIndex()).isEqualTo(0);
    assertThat(result.matchedWords()).containsExactly("brown", "fox");
  }

  @Test
  void findLongestMatch_matchInMiddle() {
    List<String> text1 = List.of("hello", "world", "this", "is", "a", "test");
    List<String> text2 = List.of("some", "words", "this", "is", "a", "test", "here");

    MatchResult result = LongestMatchFinder.findLongestMatch(text1, text2, 3);

    assertThat(result.hasMatch()).isTrue();
    assertThat(result.matchLength()).isEqualTo(4);
    assertThat(result.matchedWords()).containsExactly("this", "is", "a", "test");
  }

  @Test
  void findLongestMatch_multipleMatches_choosesLongest() {
    List<String> text1 = List.of("the", "cat", "sat", "on", "the", "mat");
    List<String> text2 = List.of("the", "dog", "sat", "on", "the", "mat", "too");

    MatchResult result = LongestMatchFinder.findLongestMatch(text1, text2, 2);

    assertThat(result.hasMatch()).isTrue();
    assertThat(result.matchLength()).isEqualTo(4); // "sat on the mat"
    assertThat(result.matchedWords()).containsExactly("sat", "on", "the", "mat");
  }

  @Test
  void findLongestMatch_caseInsensitive() {
    List<String> text1 = List.of("Hello", "World");
    List<String> text2 = List.of("hello", "world", "again");

    MatchResult result = LongestMatchFinder.findLongestMatch(text1, text2, 2);

    assertThat(result.hasMatch()).isTrue();
    assertThat(result.matchLength()).isEqualTo(2);
  }

  @Test
  void findLongestMatch_withPunctuation() {
    List<String> text1 = List.of("hello", "world.");
    List<String> text2 = List.of("hello", "world", "again");

    MatchResult result = LongestMatchFinder.findLongestMatch(text1, text2, 2);

    assertThat(result.hasMatch()).isTrue();
    assertThat(result.matchLength()).isEqualTo(2);
  }

  @Test
  void findLongestMatch_noMatch() {
    List<String> text1 = List.of("completely", "different", "words");
    List<String> text2 = List.of("nothing", "in", "common");

    MatchResult result = LongestMatchFinder.findLongestMatch(text1, text2, 2);

    assertThat(result.hasMatch()).isFalse();
    assertThat(result.matchLength()).isEqualTo(0);
  }

  @Test
  void findLongestMatch_belowMinimum() {
    List<String> text1 = List.of("the", "cat");
    List<String> text2 = List.of("the", "dog");

    MatchResult result = LongestMatchFinder.findLongestMatch(text1, text2, 3);

    assertThat(result.hasMatch()).isFalse(); // Only 1 word matches, below minimum of 3
  }

  @Test
  void findLongestMatch_emptyInput() {
    List<String> text1 = List.of();
    List<String> text2 = List.of("some", "words");

    MatchResult result = LongestMatchFinder.findLongestMatch(text1, text2, 2);

    assertThat(result.hasMatch()).isFalse();
  }

  @Test
  void findLongestMatch_realWorldExample() {
    // Simulating actual overlap from transcription
    List<String> text1 =
        List.of(
            "and",
            "that's",
            "why",
            "we",
            "need",
            "to",
            "focus",
            "on",
            "the",
            "customer",
            "experience",
            "because",
            "at",
            "the",
            "end",
            "of",
            "the",
            "day");

    List<String> text2 =
        List.of(
            "because",
            "at",
            "the",
            "end",
            "of",
            "the",
            "day",
            "it's",
            "all",
            "about",
            "delivering",
            "value");

    MatchResult result = LongestMatchFinder.findLongestMatch(text1, text2, 3);

    assertThat(result.hasMatch()).isTrue();
    assertThat(result.matchLength()).isEqualTo(7);
    assertThat(result.matchedWords())
        .containsExactly("because", "at", "the", "end", "of", "the", "day");
  }

  @Test
  void extractWords_fromText() {
    String text = "Hello world, this is a test!";
    List<String> words = LongestMatchFinder.extractWords(text);

    assertThat(words).containsExactly("Hello", "world,", "this", "is", "a", "test!");
  }

  @Test
  void extractWords_handlesMultipleSpaces() {
    String text = "Hello    world   test";
    List<String> words = LongestMatchFinder.extractWords(text);

    assertThat(words).containsExactly("Hello", "world", "test");
  }

  @Test
  void normalizeWord_removesCommonPunctuation() {
    // This is tested indirectly through the matching tests
    List<String> text1 = List.of("hello,", "world!");
    List<String> text2 = List.of("hello", "world");

    MatchResult result = LongestMatchFinder.findLongestMatch(text1, text2, 2);

    assertThat(result.hasMatch()).isTrue();
    assertThat(result.matchLength()).isEqualTo(2);
  }
}
