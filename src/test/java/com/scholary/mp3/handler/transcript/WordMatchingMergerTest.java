package com.scholary.mp3.handler.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import com.scholary.mp3.handler.whisper.TranscriptSegment;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WordMatchingMergerTest {

  private WordMatchingMerger merger;

  @BeforeEach
  void setUp() {
    merger = new WordMatchingMerger();
  }

  @Test
  void merge_singleChunk_returnsAsIs() {
    ChunkTranscript chunk =
        new ChunkTranscript(
            0,
            0.0,
            List.of(
                new TranscriptSegment(0.0, 2.0, "Hello world"),
                new TranscriptSegment(2.0, 4.0, "This is a test")),
            "en");

    List<MergedSegment> result = merger.merge(List.of(chunk));

    assertThat(result).hasSize(2);
    assertThat(result.get(0).text()).isEqualTo("Hello world");
    assertThat(result.get(1).text()).isEqualTo("This is a test");
  }

  @Test
  void merge_twoChunks_withPerfectOverlap() {
    // Chunk 1: 0-60s, ends with "because at the end of the day"
    ChunkTranscript chunk1 =
        new ChunkTranscript(
            0,
            0.0,
            List.of(
                new TranscriptSegment(0.0, 5.0, "Hello world this is the first chunk"),
                new TranscriptSegment(
                    50.0, 60.0, "and we're ending with because at the end of the day")),
            "en");

    // Chunk 2: 30-90s (30s overlap), starts with "because at the end of the day"
    ChunkTranscript chunk2 =
        new ChunkTranscript(
            30.0,
            30.0,
            List.of(
                new TranscriptSegment(
                    0.0, 10.0, "because at the end of the day it's all about value"),
                new TranscriptSegment(10.0, 20.0, "and this is the second chunk")),
            "en");

    List<MergedSegment> result = merger.merge(List.of(chunk1, chunk2));

    // Should have 3 segments:
    // 1. First segment from chunk1
    // 2. Merged overlap (keeping chunk1's version up to match, then chunk2 from match)
    // 3. Second segment from chunk2
    assertThat(result).hasSize(3);

    // First segment unchanged
    assertThat(result.get(0).text()).isEqualTo("Hello world this is the first chunk");
    assertThat(result.get(0).start()).isEqualTo(0.0);

    // Overlap merged - should start with chunk1's version
    assertThat(result.get(1).text())
        .contains("because at the end of the day")
        .contains("it's all about value");

    // Last segment from chunk2
    assertThat(result.get(2).text()).isEqualTo("and this is the second chunk");
    assertThat(result.get(2).start()).isEqualTo(40.0); // 30.0 offset + 10.0
  }

  @Test
  void merge_twoChunks_noOverlapMatch_fallsBackToConcatenation() {
    // Chunk 1: ends with completely different text
    ChunkTranscript chunk1 =
        new ChunkTranscript(
            0, 0.0, List.of(new TranscriptSegment(0.0, 5.0, "First chunk content")), "en");

    // Chunk 2: starts with completely different text (no match)
    ChunkTranscript chunk2 =
        new ChunkTranscript(
            30.0, 30.0, List.of(new TranscriptSegment(0.0, 5.0, "Second chunk content")), "en");

    List<MergedSegment> result = merger.merge(List.of(chunk1, chunk2));

    // Should concatenate both chunks
    assertThat(result).hasSize(2);
    assertThat(result.get(0).text()).isEqualTo("First chunk content");
    assertThat(result.get(1).text()).isEqualTo("Second chunk content");
  }

  @Test
  void merge_threeChunks_withOverlaps() {
    ChunkTranscript chunk1 =
        new ChunkTranscript(
            0,
            0.0,
            List.of(
                new TranscriptSegment(0.0, 10.0, "First chunk"),
                new TranscriptSegment(50.0, 60.0, "ending with the quick brown fox")),
            "en");

    ChunkTranscript chunk2 =
        new ChunkTranscript(
            30.0,
            30.0,
            List.of(
                new TranscriptSegment(0.0, 10.0, "the quick brown fox jumps over"),
                new TranscriptSegment(50.0, 60.0, "ending with lorem ipsum dolor")),
            "en");

    ChunkTranscript chunk3 =
        new ChunkTranscript(
            60.0,
            60.0,
            List.of(
                new TranscriptSegment(0.0, 10.0, "lorem ipsum dolor sit amet"),
                new TranscriptSegment(10.0, 20.0, "final chunk")),
            "en");

    List<MergedSegment> result = merger.merge(List.of(chunk1, chunk2, chunk3));

    // Should merge all three chunks with overlaps resolved
    assertThat(result).isNotEmpty();

    // Verify no duplicate phrases
    String fullText = result.stream().map(MergedSegment::text).reduce("", (a, b) -> a + " " + b);

    // "the quick brown fox" should appear once
    int count1 = countOccurrences(fullText, "the quick brown fox");
    assertThat(count1).isLessThanOrEqualTo(1);

    // "lorem ipsum dolor" should appear once
    int count2 = countOccurrences(fullText, "lorem ipsum dolor");
    assertThat(count2).isLessThanOrEqualTo(1);
  }

  @Test
  void merge_emptyChunks_returnsEmpty() {
    List<MergedSegment> result = merger.merge(List.of());
    assertThat(result).isEmpty();
  }

  @Test
  void merge_chunkWithNoSegments_handlesGracefully() {
    ChunkTranscript emptyChunk = new ChunkTranscript(0, 0.0, List.of(), "en");

    List<MergedSegment> result = merger.merge(List.of(emptyChunk));

    assertThat(result).isEmpty();
  }

  @Test
  void merge_preservesTimestamps() {
    ChunkTranscript chunk1 =
        new ChunkTranscript(
            0, 0.0, List.of(new TranscriptSegment(10.0, 20.0, "First segment")), "en");

    ChunkTranscript chunk2 =
        new ChunkTranscript(
            100.0, 100.0, List.of(new TranscriptSegment(5.0, 15.0, "Second segment")), "en");

    List<MergedSegment> result = merger.merge(List.of(chunk1, chunk2));

    // Timestamps should be adjusted by chunk offset
    assertThat(result.get(0).start()).isEqualTo(10.0); // 0 + 10
    assertThat(result.get(0).end()).isEqualTo(20.0); // 0 + 20
    assertThat(result.get(1).start()).isEqualTo(105.0); // 100 + 5
    assertThat(result.get(1).end()).isEqualTo(115.0); // 100 + 15
  }

  @Test
  void merge_realWorldScenario_8hourFile() {
    // Simulate 8 chunks from an 8-hour file with 30s overlaps
    List<ChunkTranscript> chunks = List.of(
        createChunk(0, 0.0, "Chunk 0 content", "overlap phrase one"),
        createChunk(1, 3570.0, "overlap phrase one", "overlap phrase two"),
        createChunk(2, 7140.0, "overlap phrase two", "overlap phrase three"),
        createChunk(3, 10710.0, "overlap phrase three", "overlap phrase four"),
        createChunk(4, 14280.0, "overlap phrase four", "overlap phrase five"),
        createChunk(5, 17850.0, "overlap phrase five", "overlap phrase six"),
        createChunk(6, 21420.0, "overlap phrase six", "overlap phrase seven"),
        createChunk(7, 24990.0, "overlap phrase seven", "final content")
    );

    List<MergedSegment> result = merger.merge(chunks);

    // Should have merged all chunks
    assertThat(result).isNotEmpty();

    // Each overlap phrase should appear only once
    String fullText = result.stream().map(MergedSegment::text).reduce("", (a, b) -> a + " " + b);
    
    for (int i = 1; i <= 7; i++) {
      String phrase = "overlap phrase " + (i == 1 ? "one" : i == 2 ? "two" : i == 3 ? "three" : 
                                          i == 4 ? "four" : i == 5 ? "five" : i == 6 ? "six" : "seven");
      int count = countOccurrences(fullText, phrase);
      assertThat(count).as("Phrase '%s' should appear once", phrase).isLessThanOrEqualTo(1);
    }
  }

  // Helper methods

  private ChunkTranscript createChunk(int index, double offset, String startText, String endText) {
    return new ChunkTranscript(
        index,
        offset,
        List.of(
            new TranscriptSegment(0.0, 30.0, startText + " middle content"),
            new TranscriptSegment(3570.0, 3600.0, "more content " + endText)),
        "en");
  }

  private int countOccurrences(String text, String phrase) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(phrase, index)) != -1) {
      count++;
      index += phrase.length();
    }
    return count;
  }
}
