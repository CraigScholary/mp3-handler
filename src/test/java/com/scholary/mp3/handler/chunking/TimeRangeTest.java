package com.scholary.mp3.handler.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TimeRangeTest {

  @Test
  void constructor_shouldRejectNegativeStart() {
    assertThatThrownBy(() -> new TimeRange(-1.0, 10.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
  }

  @Test
  void constructor_shouldRejectEndBeforeStart() {
    assertThatThrownBy(() -> new TimeRange(10.0, 5.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("end time must be >= start time");
  }

  @Test
  void duration_shouldCalculateCorrectly() {
    TimeRange range = new TimeRange(10.0, 25.5);
    assertThat(range.duration()).isEqualTo(15.5);
  }

  @Test
  void contains_shouldReturnTrueForTimeWithinRange() {
    TimeRange range = new TimeRange(10.0, 20.0);
    assertThat(range.contains(15.0)).isTrue();
    assertThat(range.contains(10.0)).isTrue();
    assertThat(range.contains(20.0)).isTrue();
  }

  @Test
  void contains_shouldReturnFalseForTimeOutsideRange() {
    TimeRange range = new TimeRange(10.0, 20.0);
    assertThat(range.contains(5.0)).isFalse();
    assertThat(range.contains(25.0)).isFalse();
  }

  @Test
  void overlaps_shouldDetectOverlappingRanges() {
    TimeRange range1 = new TimeRange(10.0, 20.0);
    TimeRange range2 = new TimeRange(15.0, 25.0);
    assertThat(range1.overlaps(range2)).isTrue();
    assertThat(range2.overlaps(range1)).isTrue();
  }

  @Test
  void overlaps_shouldReturnFalseForNonOverlappingRanges() {
    TimeRange range1 = new TimeRange(10.0, 20.0);
    TimeRange range2 = new TimeRange(20.0, 30.0);
    assertThat(range1.overlaps(range2)).isFalse();
  }
}
