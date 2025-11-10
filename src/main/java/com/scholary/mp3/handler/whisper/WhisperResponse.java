package com.scholary.mp3.handler.whisper;

import java.util.List;

/**
 * Response from the Whisper transcription API.
 *
 * <p>Contains a list of segments and the detected language.
 */
public record WhisperResponse(List<TranscriptSegment> segments, String language) {}
