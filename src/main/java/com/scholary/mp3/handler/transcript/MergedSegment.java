package com.scholary.mp3.handler.transcript;

/**
 * A transcript segment with absolute timing in the original audio file.
 *
 * <p>After merging chunks, all segment timestamps are adjusted to reflect their position in the
 * full audio file, not just within their chunk.
 */
public record MergedSegment(double start, double end, String text) {}
