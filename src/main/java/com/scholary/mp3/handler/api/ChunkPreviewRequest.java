package com.scholary.mp3.handler.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request for previewing chunk boundaries without processing.
 *
 * <p>Useful for debugging and understanding how the file will be split.
 */
public record ChunkPreviewRequest(
    @NotBlank String bucket,
    @NotBlank String key,
    @Min(10) @Max(300) Integer chunkSeconds,
    @Min(0) @Max(30) Integer overlapSeconds,
    Boolean silenceAware) {

  public ChunkPreviewRequest {
    if (chunkSeconds == null) {
      chunkSeconds = 60;
    }
    if (overlapSeconds == null) {
      overlapSeconds = 5;
    }
    if (silenceAware == null) {
      silenceAware = true;
    }
  }
}
