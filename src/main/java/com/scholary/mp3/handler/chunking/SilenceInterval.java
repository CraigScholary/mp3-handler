package com.scholary.mp3.handler.chunking;

/**
 * Represents a detected silence interval in an audio file.
 *
 * <p>Silence detection helps us find natural break points for chunking. Instead of cutting mid-word
 * or mid-sentence, we can nudge chunk boundaries to align with silence.
 */
public record SilenceInterval(double start, double end) {

  public double duration() {
    return end - start;
  }

  /**
   * Get the midpoint of this silence interval.
   *
   * <p>When nudging a chunk boundary, we typically aim for the middle of a silence gap to maximize
   * the buffer on both sides.
   *
   * @return the midpoint time in seconds
   */
  public double midpoint() {
    return (start + end) / 2.0;
  }
}
