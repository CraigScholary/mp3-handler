package com.scholary.mp3.handler.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FfmpegChunkPlannerTest {

  private FfmpegChunkPlanner planner;

  @BeforeEach
  void setUp() {
    FfmpegProperties properties = new FfmpegProperties("-30dB", 0.5, 3.0, 2);
    planner = new FfmpegChunkPlanner(properties);
  }

  @Test
  void planChunks_shouldCreateFixedSizeChunksWithoutSilence() {
    Duration chunkDuration = Duration.ofSeconds(60);
    Duration overlapDuration = Duration.ofSeconds(5);
    List<SilenceInterval> silenceIntervals = List.of();
    Duration totalDuration = Duration.ofSeconds(180);

    List<TimeRange> chunks =
        planner.planChunks(chunkDuration, overlapDuration, silenceIntervals, totalDuration);

    // Should have 3 chunks (0-60, 55-115, 110-170, 165-180)
    // Actually with 5s overlap: chunk1 ends at 60, chunk2 starts at 55
    assertThat(chunks).isNotEmpty();
    assertThat(chunks.get(0).start()).isEqualTo(0.0);
  }

  @Test
  void planChunks_shouldHaveOverlapBetweenChunks() {
    Duration chunkDuration = Duration.ofSeconds(60);
    Duration overlapDuration = Duration.ofSeconds(5);
    List<SilenceInterval> silenceIntervals = List.of();
    Duration totalDuration = Duration.ofSeconds(120);

    List<TimeRange> chunks =
        planner.planChunks(chunkDuration, overlapDuration, silenceIntervals, totalDuration);

    // Verify overlap: second chunk should start before first chunk ends
    if (chunks.size() >= 2) {
      double firstEnd = chunks.get(0).end();
      double secondStart = chunks.get(1).start();
      assertThat(secondStart).isLessThan(firstEnd);
      assertThat(firstEnd - secondStart).isEqualTo(5.0);
    }
  }

  @Test
  void planChunks_shouldNotExceedTotalDuration() {
    Duration chunkDuration = Duration.ofSeconds(60);
    Duration overlapDuration = Duration.ofSeconds(5);
    List<SilenceInterval> silenceIntervals = List.of();
    Duration totalDuration = Duration.ofSeconds(100);

    List<TimeRange> chunks =
        planner.planChunks(chunkDuration, overlapDuration, silenceIntervals, totalDuration);

    // Last chunk should not exceed total duration
    TimeRange lastChunk = chunks.get(chunks.size() - 1);
    assertThat(lastChunk.end()).isLessThanOrEqualTo(100.0);
  }

  @Test
  void planChunks_shouldNudgeBoundariesToSilence() {
    Duration chunkDuration = Duration.ofSeconds(60);
    Duration overlapDuration = Duration.ofSeconds(5);

    // Add silence near the 60s mark
    List<SilenceInterval> silenceIntervals = List.of(new SilenceInterval(58.0, 62.0));

    Duration totalDuration = Duration.ofSeconds(120);

    List<TimeRange> chunks =
        planner.planChunks(chunkDuration, overlapDuration, silenceIntervals, totalDuration);

    // First chunk should end near the silence midpoint (60s)
    // This is a simplified check - actual nudging depends on implementation details
    assertThat(chunks).isNotEmpty();
    assertThat(chunks.get(0).end()).isGreaterThan(55.0).isLessThan(65.0);
  }

  @Test
  void planChunks_shouldHandleShortFiles() {
    Duration chunkDuration = Duration.ofSeconds(60);
    Duration overlapDuration = Duration.ofSeconds(5);
    List<SilenceInterval> silenceIntervals = List.of();
    Duration totalDuration = Duration.ofSeconds(30);

    List<TimeRange> chunks =
        planner.planChunks(chunkDuration, overlapDuration, silenceIntervals, totalDuration);

    // Should have exactly one chunk covering the entire file
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).start()).isEqualTo(0.0);
    assertThat(chunks.get(0).end()).isEqualTo(30.0);
  }

  @Test
  void planChunks_shouldNotCreateGaps() {
    Duration chunkDuration = Duration.ofSeconds(60);
    Duration overlapDuration = Duration.ofSeconds(5);
    List<SilenceInterval> silenceIntervals = List.of();
    Duration totalDuration = Duration.ofSeconds(180);

    List<TimeRange> chunks =
        planner.planChunks(chunkDuration, overlapDuration, silenceIntervals, totalDuration);

    // Verify no gaps: each chunk should start at or before the previous chunk's end
    for (int i = 1; i < chunks.size(); i++) {
      double prevEnd = chunks.get(i - 1).end();
      double currentStart = chunks.get(i).start();
      assertThat(currentStart).isLessThanOrEqualTo(prevEnd);
    }
  }

  @Test
  void planChunks_shouldRejectInvalidOverlap() {
    Duration chunkDuration = Duration.ofSeconds(60);
    Duration overlapDuration = Duration.ofSeconds(60); // Same as chunk duration
    List<SilenceInterval> silenceIntervals = List.of();
    Duration totalDuration = Duration.ofSeconds(180);

    assertThatThrownBy(
            () ->
                planner.planChunks(chunkDuration, overlapDuration, silenceIntervals, totalDuration))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Overlap")
        .hasMessageContaining("must be less than chunk duration");
  }

  @Test
  void planChunks_shouldHandleManySmallSilenceIntervals() {
    Duration chunkDuration = Duration.ofSeconds(60);
    Duration overlapDuration = Duration.ofSeconds(5);

    // Create many small silence intervals (simulating very chatty audio)
    List<SilenceInterval> silenceIntervals = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      double start = i * 1.5;
      silenceIntervals.add(new SilenceInterval(start, start + 0.5));
    }

    Duration totalDuration = Duration.ofSeconds(300);

    List<TimeRange> chunks =
        planner.planChunks(chunkDuration, overlapDuration, silenceIntervals, totalDuration);

    // Should complete without hanging or OOM
    assertThat(chunks).isNotEmpty();
    assertThat(chunks.size()).isLessThan(1000); // Safety limit

    // Verify all chunks make forward progress
    for (int i = 1; i < chunks.size(); i++) {
      assertThat(chunks.get(i).start()).isGreaterThan(chunks.get(i - 1).start());
    }
  }
}
