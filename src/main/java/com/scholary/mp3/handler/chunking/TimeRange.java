package com.scholary.mp3.handler.chunking;

/**
 * Represents a time range in seconds with start and end points.
 *
 * <p>Used for defining chunk boundaries, silence intervals, and other time-based segments. All
 * times are in seconds with fractional precision.
 */
public record TimeRange(double start, double end) {

  public TimeRange {
    if (start < 0) {
      throw new IllegalArgumentException("Start time cannot be negative");
    }
    if (end < start) {
      throw new IllegalArgumentException("End time must be >= start time");
    }
  }

  public double duration() {
    return end - start;
  }

  /**
   * Check if this range contains a given time point.
   *
   * @param time the time to check
   * @return true if time is within [start, end]
   */
  public boolean contains(double time) {
    return time >= start && time <= end;
  }

  /**
   * Check if this range overlaps with another range.
   *
   * @param other the other range
   * @return true if the ranges overlap
   */
  public boolean overlaps(TimeRange other) {
    return this.start < other.end && other.start < this.end;
  }
}
