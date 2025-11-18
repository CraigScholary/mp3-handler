package com.scholary.mp3.handler.api;

/**
 * Chunking mode for transcription.
 */
public enum ChunkingMode {
  /**
   * Fixed-interval chunking with overlaps.
   *
   * <p>Uses word matching to merge overlapping regions.
   */
  OVERLAP,

  /**
   * Silence-aware chunking at natural pauses.
   *
   * <p>Uses simple concatenation (no overlaps).
   */
  SILENCE_AWARE
}
