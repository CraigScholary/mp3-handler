package com.scholary.mp3.handler.job;

import com.scholary.mp3.handler.api.JobStatusResponse.Status;
import com.scholary.mp3.handler.api.TranscriptionRequest;
import com.scholary.mp3.handler.api.TranscriptionResponse;
import java.time.Instant;

/**
 * Represents an async transcription job.
 *
 * <p>Tracks the job's state, progress, and result. Stored in memory using Caffeine cache.
 */
public class TranscriptionJob {

  private final String jobId;
  private final TranscriptionRequest request;
  private final Instant createdAt;

  private Status status;
  private Integer progress; // 0-100
  private TranscriptionResponse result;
  private String error;

  public TranscriptionJob(String jobId, TranscriptionRequest request) {
    this.jobId = jobId;
    this.request = request;
    this.createdAt = Instant.now();
    this.status = Status.PENDING;
    this.progress = 0;
  }

  public String getJobId() {
    return jobId;
  }

  public TranscriptionRequest getRequest() {
    return request;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public Integer getProgress() {
    return progress;
  }

  public void setProgress(Integer progress) {
    this.progress = progress;
  }

  public TranscriptionResponse getResult() {
    return result;
  }

  public void setResult(TranscriptionResponse result) {
    this.result = result;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }
}
