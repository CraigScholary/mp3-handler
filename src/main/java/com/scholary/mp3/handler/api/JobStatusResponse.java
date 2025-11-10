package com.scholary.mp3.handler.api;

/**
 * Response for job status query.
 *
 * <p>Shows the current state of an async job and includes the result if completed.
 */
public record JobStatusResponse(
    String jobId,
    Status status,
    Integer progress,
    TranscriptionResponse result,
    String error,
    String kibanaUrl) {

  public enum Status {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
  }
}
