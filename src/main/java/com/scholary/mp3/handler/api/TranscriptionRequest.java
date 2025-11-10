package com.scholary.mp3.handler.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request for transcribing an MP3 file.
 *
 * <p>Specifies the object storage location and processing parameters. All transcriptions are
 * asynchronous - returns a job ID immediately, client polls /jobs/{id} for status and progress.
 */
public record TranscriptionRequest(
    @NotBlank String bucket,
    @NotBlank String key,
    @Min(10) @Max(300) Integer chunkSeconds,
    @Min(0) @Max(30) Integer overlapSeconds,
    Boolean silenceAware,
    Boolean save) {

  // Provide defaults
  public TranscriptionRequest {
    if (chunkSeconds == null) {
      chunkSeconds = 60;
    }
    if (overlapSeconds == null) {
      overlapSeconds = 5;
    }
    if (silenceAware == null) {
      silenceAware = true;
    }
    if (save == null) {
      save = true;
    }
  }
}
