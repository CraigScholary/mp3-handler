package com.scholary.mp3.handler.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import com.scholary.mp3.handler.whisper.TranscriptSegment;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranscriptMergerTest {

  private TranscriptMerger merger;

  @BeforeEach
  void setUp() {
    merger = new TranscriptMerger();
  }

  @Test
  void merge_shouldAdjustTimestampsByChunkOffset() {
    ChunkTranscript chunk1 =
        new ChunkTranscript(
            0,
            0.0,
            List.of(
                new TranscriptSegment(0.0, 5.0, "Hello"),
                new TranscriptSegment(5.0, 10.0, "World")),
            "en");

    ChunkTranscript chunk2 =
        new ChunkTranscript(
            1,
            55.0, // Chunk starts at 55s in original file
            List.of(
                new TranscriptSegment(0.0, 5.0, "Goodbye"),
                new TranscriptSegment(5.0, 10.0, "Everyone")),
            "en");

    List<MergedSegment> merged = merger.merge(List.of(chunk1, chunk2));

    // First chunk segments should have original timestamps
    assertThat(merged.get(0).start()).isEqualTo(0.0);
    assertThat(merged.get(0).end()).isEqualTo(5.0);

    // Second chunk segments should be offset by 55s
    // (assuming no duplicates detected)
    boolean hasOffsetSegment =
        merged.stream().anyMatch(s -> s.start() >= 55.0 && s.text().equals("Goodbye"));
    assertThat(hasOffsetSegment).isTrue();
  }

  @Test
  void merge_shouldRemoveDuplicatesInOverlap() {
    // Create two chunks with overlapping content
    ChunkTranscript chunk1 =
        new ChunkTranscript(
            0,
            0.0,
            List.of(
                new TranscriptSegment(0.0, 5.0, "Hello world"),
                new TranscriptSegment(5.0, 10.0, "This is a test")),
            "en");

    // Chunk 2 starts at 5s, so it overlaps with chunk 1's second segment
    ChunkTranscript chunk2 =
        new ChunkTranscript(
            1,
            5.0,
            List.of(
                new TranscriptSegment(0.0, 5.0, "This is a test"), // Duplicate
                new TranscriptSegment(5.0, 10.0, "New content")),
            "en");

    List<MergedSegment> merged = merger.merge(List.of(chunk1, chunk2));

    // Should not have exact duplicates
    // Count occurrences of "This is a test"
    long count = merged.stream().filter(s -> s.text().equals("This is a test")).count();
    assertThat(count).isLessThanOrEqualTo(1);
  }

  @Test
  void merge_shouldPreserveSegmentOrder() {
    ChunkTranscript chunk1 =
        new ChunkTranscript(
            0,
            0.0,
            List.of(
                new TranscriptSegment(0.0, 5.0, "First"),
                new TranscriptSegment(5.0, 10.0, "Second")),
            "en");

    ChunkTranscript chunk2 =
        new ChunkTranscript(
            1,
            60.0,
            List.of(
                new TranscriptSegment(0.0, 5.0, "Third"),
                new TranscriptSegment(5.0, 10.0, "Fourth")),
            "en");

    List<MergedSegment> merged = merger.merge(List.of(chunk1, chunk2));

    // Timestamps should be monotonically increasing
    for (int i = 1; i < merged.size(); i++) {
      assertThat(merged.get(i).start()).isGreaterThanOrEqualTo(merged.get(i - 1).start());
    }
  }

  @Test
  void merge_shouldHandleSingleChunk() {
    ChunkTranscript chunk =
        new ChunkTranscript(
            0,
            0.0,
            List.of(
                new TranscriptSegment(0.0, 5.0, "Hello"),
                new TranscriptSegment(5.0, 10.0, "World")),
            "en");

    List<MergedSegment> merged = merger.merge(List.of(chunk));

    assertThat(merged).hasSize(2);
    assertThat(merged.get(0).text()).isEqualTo("Hello");
    assertThat(merged.get(1).text()).isEqualTo("World");
  }

  @Test
  void merge_shouldHandleEmptyChunks() {
    List<MergedSegment> merged = merger.merge(List.of());
    assertThat(merged).isEmpty();
  }
}
