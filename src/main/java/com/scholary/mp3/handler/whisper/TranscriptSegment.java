package com.scholary.mp3.handler.whisper;

/**
 * Represents a single segment of transcribed audio.
 *
 * <p>This matches the structure returned by the Whisper service. Each segment has timing and text
 * information.
 */
public record TranscriptSegment(double start, double end, String text) {}
