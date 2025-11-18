package com.scholary.mp3.handler.chunking;

/**
 * Represents a chunk breakpoint with its associated silence interval.
 *
 * <p>This allows us to intelligently place overlaps WITHIN the silence region, ensuring we don't
 * cut mid-word while still providing safety margins.
 */
public record BreakpointWithSilence(
    double breakpoint, SilenceInterval silence, boolean hasSilence) {

  /**
   * Create a breakpoint with associated silence.
   *
   * @param breakpoint the time position where the chunk should be cut
   * @param silence the silence interval that contains this breakpoint
   */
  public BreakpointWithSilence(double breakpoint, SilenceInterval silence) {
    this(breakpoint, silence, true);
  }

  /**
   * Create a breakpoint without silence (forced cut).
   *
   * @param breakpoint the time position where the chunk should be cut
   */
  public BreakpointWithSilence(double breakpoint) {
    this(breakpoint, null, false);
  }

  /**
   * Calculate safe overlap within the silence region.
   *
   * <p>Returns the maximum overlap that can be applied without extending beyond the silence. If
   * there's no silence, returns 0.
   *
   * @param requestedOverlap the desired overlap in seconds
   * @return the safe overlap that fits within the silence
   */
  public double getSafeOverlap(double requestedOverlap) {
    if (!hasSilence || silence == null) {
      return 0.0; // No silence = no safe overlap
    }

    // Maximum overlap is limited by silence duration
    // We want to stay within the silence boundaries
    double silenceDuration = silence.duration();
    return Math.min(requestedOverlap, silenceDuration * 0.8); // Use 80% of silence for safety
  }

  /**
   * Get the chunk end position with overlap applied.
   *
   * <p>For silence-aware: extends into the silence but not beyond it For forced cuts: no overlap
   *
   * @param requestedOverlap the desired overlap in seconds
   * @return the adjusted end position
   */
  public double getChunkEnd(double requestedOverlap) {
    if (!hasSilence) {
      return breakpoint; // Forced cut = no overlap
    }

    double safeOverlap = getSafeOverlap(requestedOverlap);
    double halfOverlap = safeOverlap / 2.0;

    // Extend to breakpoint + half overlap, but don't exceed silence end
    return Math.min(breakpoint + halfOverlap, silence.end());
  }

  /**
   * Get the next chunk start position with overlap applied.
   *
   * <p>For silence-aware: starts before the breakpoint but not before silence start For forced
   * cuts: starts at breakpoint (no overlap)
   *
   * @param requestedOverlap the desired overlap in seconds
   * @return the adjusted start position
   */
  public double getNextChunkStart(double requestedOverlap) {
    if (!hasSilence) {
      return breakpoint; // Forced cut = no overlap
    }

    double safeOverlap = getSafeOverlap(requestedOverlap);
    double halfOverlap = safeOverlap / 2.0;

    // Start at breakpoint - half overlap, but don't go before silence start
    return Math.max(breakpoint - halfOverlap, silence.start());
  }
}
