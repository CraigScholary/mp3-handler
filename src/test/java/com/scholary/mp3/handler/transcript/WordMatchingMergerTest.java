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
    merger = new WordMatchingMerger(new LongestMatchFinder());
  }

  @Test
  void merge_removesOverlappingContent() {
    // Simulate the duplicate issue from the bug report
    // Chunk 0: 0-300s, ends with "thanks Stephen I'm still sharing the idea"
    ChunkTranscript chunk0 = new ChunkTranscript(
        0,
        0.0,
        300.0,
        List.of(
            new TranscriptSegment(289.05, 295.05, 
                "And I just get them all to agree. And if they do, but in after I've said that, I can let them say the thing, okay, thanks Stephen."),
            new TranscriptSegment(295.05, 300.05, 
                "I'm still sharing the idea. I'll come back to your question in a moment. And you have to reclaim your land.")
        ),
        "en"
    );

    // Chunk 1: 268.41-568.41s, starts with overlapping content
    // The first few segments are duplicates of chunk0's ending
    ChunkTranscript chunk1 = new ChunkTranscript(
        1,
        268.41,
        568.41,
        List.of(
            // These segments overlap with chunk0 and should be removed
            new TranscriptSegment(0.0, 2.48, "thanks Stephen"),
            new TranscriptSegment(2.48, 4.2, "I'm still sharing the idea."),
            new TranscriptSegment(4.2, 5.92, "I'll come back to your question in a moment."),
            new TranscriptSegment(5.92, 7.72, "And you have to reclaim your land."),
            // These segments are new content and should be kept
            new TranscriptSegment(7.72, 10.0, "This is new content that should appear."),
            new TranscriptSegment(10.0, 12.5, "And this is also new content.")
        ),
        "en"
    );

    List<MergedSegment> result = merger.merge(chunk0, chunk1);

    // Should only contain the non-overlapping segments from chunk1
    assertThat(result).hasSize(2);
    
    // Verify the segments are the new content (with absolute timestamps)
    assertThat(result.get(0).text()).isEqualTo("This is new content that should appear.");
    assertThat(result.get(0).start()).isCloseTo(268.41 + 7.72, within(0.01));
    
    assertThat(result.get(1).text()).isEqualTo("And this is also new content.");
    assertThat(result.get(1).start()).isCloseTo(268.41 + 10.0, within(0.01));
  }

  @Test
  void merge_handlesNoOverlap() {
    // Chunks that don't overlap at all
    ChunkTranscript chunk0 = new ChunkTranscript(
        0,
        0.0,
        100.0,
        List.of(
            new TranscriptSegment(0.0, 5.0, "First chunk content")
        ),
        "en"
    );

    ChunkTranscript chunk1 = new ChunkTranscript(
        1,
        100.0,
        200.0,
        List.of(
            new TranscriptSegment(0.0, 5.0, "Second chunk content")
        ),
        "en"
    );

    List<MergedSegment> result = merger.merge(chunk0, chunk1);

    // Should contain all segments from chunk1 since there's no overlap
    assertThat(result).hasSize(1);
    assertThat(result.get(0).text()).isEqualTo("Second chunk content");
  }

  @Test
  void merge_handlesPartialSegmentOverlap() {
    // Test case where a segment contains both overlapping and new words
    ChunkTranscript chunk0 = new ChunkTranscript(
        0,
        0.0,
        100.0,
        List.of(
            new TranscriptSegment(90.0, 100.0, "the quick brown fox jumps over")
        ),
        "en"
    );

    ChunkTranscript chunk1 = new ChunkTranscript(
        1,
        80.0,
        180.0,
        List.of(
            // This segment overlaps with chunk0
            new TranscriptSegment(10.0, 20.0, "the quick brown fox jumps over"),
            // This segment is new
            new TranscriptSegment(20.0, 25.0, "the lazy dog")
        ),
        "en"
    );

    List<MergedSegment> result = merger.merge(chunk0, chunk1);

    // Should skip the overlapping segment and keep only the new one
    assertThat(result).hasSize(1);
    assertThat(result.get(0).text()).isEqualTo("the lazy dog");
    assertThat(result.get(0).start()).isCloseTo(80.0 + 20.0, within(0.01));
  }

  @Test
  void merge_realWorldDuplicateExample() {
    // Real example from the bug report: Australia/Harry Potter story around 520-568s
    ChunkTranscript chunk1 = new ChunkTranscript(
        1,
        268.41,
        568.41,
        List.of(
            new TranscriptSegment(252.09, 254.09, "And that's why when American speakers come to Australia,"),
            new TranscriptSegment(254.09, 255.53, "they're like, oh, they're so."),
            new TranscriptSegment(255.53, 257.65, "Takes them a long time to get in, Lord."),
            new TranscriptSegment(257.65, 260.45, "Because we're so skeptical in Australia, too."),
            new TranscriptSegment(260.45, 262.09, "I think we kept that as we went to Australia from the UK."),
            new TranscriptSegment(264.25, 266.17, "And they're still that."),
            new TranscriptSegment(266.17, 267.69, "So you've got to be sensitive to that.")
        ),
        "en"
    );

    ChunkTranscript chunk2 = new ChunkTranscript(
        2,
        508.41,
        808.41,
        List.of(
            // These are duplicates and should be removed
            new TranscriptSegment(12.03, 14.03, "they're like, oh, they're so."),
            new TranscriptSegment(14.03, 16.47, "Takes them a long time to get in or because we're so skeptical"),
            new TranscriptSegment(16.47, 17.55, "in Australia too."),
            new TranscriptSegment(17.55, 20.39, "I think we kept that as we went to Australia from the UK."),
            new TranscriptSegment(21.95, 24.19, "And there's still that."),
            new TranscriptSegment(24.19, 26.67, "So you've got to be sensitive to that."),
            // New content starts here
            new TranscriptSegment(26.67, 28.5, "This is new content after the overlap.")
        ),
        "en"
    );

    List<MergedSegment> result = merger.merge(chunk1, chunk2);

    // Should only keep the new content
    assertThat(result).hasSizeGreaterThanOrEqualTo(1);
    
    // Verify the last segment is the new content
    MergedSegment lastSegment = result.get(result.size() - 1);
    assertThat(lastSegment.text()).isEqualTo("This is new content after the overlap.");
  }

  @Test
  void merge_emptyPreviousChunk() {
    ChunkTranscript emptyChunk = new ChunkTranscript(0, 0.0, 100.0, List.of(), "en");
    ChunkTranscript chunk1 = new ChunkTranscript(
        1,
        100.0,
        200.0,
        List.of(new TranscriptSegment(0.0, 5.0, "Content")),
        "en"
    );

    List<MergedSegment> result = merger.merge(emptyChunk, chunk1);

    // Should return all of chunk1
    assertThat(result).hasSize(1);
    assertThat(result.get(0).text()).isEqualTo("Content");
  }

  @Test
  void merge_emptyCurrentChunk() {
    ChunkTranscript chunk0 = new ChunkTranscript(
        0,
        0.0,
        100.0,
        List.of(new TranscriptSegment(0.0, 5.0, "Content")),
        "en"
    );
    ChunkTranscript emptyChunk = new ChunkTranscript(1, 100.0, 200.0, List.of(), "en");

    List<MergedSegment> result = merger.merge(chunk0, emptyChunk);

    // Should return empty list
    assertThat(result).isEmpty();
  }

  private org.assertj.core.data.Offset<Double> within(double offset) {
    return org.assertj.core.data.Offset.offset(offset);
  }
}
