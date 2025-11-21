package com.scholary.mp3.handler.api;

import com.scholary.mp3.handler.api.JobStatusResponse.Status;
import com.scholary.mp3.handler.job.JobRepository;
import com.scholary.mp3.handler.job.TranscriptionJob;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for MP3 transcription.
 *
 * <p>Provides endpoints for:
 *
 * <ul>
 *   <li>Previewing chunk boundaries
 *   <li>Asynchronous transcription (returns job ID immediately)
 *   <li>Job status polling
 * </ul>
 *
 * <p>All transcriptions are asynchronous to support long-running jobs and progress monitoring in
 * Kibana.
 */
@RestController
@Tag(name = "Transcription", description = "MP3 transcription and chunking API")
public class TranscriptionController {

  private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptionController.class);

  private final com.scholary.mp3.handler.service.TranscriptionService transcriptionService;
  private final JobRepository jobRepository;

  public TranscriptionController(
      com.scholary.mp3.handler.service.TranscriptionService transcriptionService,
      JobRepository jobRepository) {
    this.transcriptionService = transcriptionService;
    this.jobRepository = jobRepository;
  }

  /**
   * Start asynchronous transcription job.
   */
  @PostMapping("/api/transcribe")
  @Operation(
      summary = "Start transcription",
      description = "Start asynchronous transcription job and return job ID for status polling")
  public ResponseEntity<AsyncJobResponse> transcribe(@Valid @RequestBody TranscriptionRequest request) {
    String jobId = UUID.randomUUID().toString();
    try {
      LOGGER.info("Transcription request: bucket={}, key={}", request.bucket(), request.key());

      // Create job
      TranscriptionJob job = new TranscriptionJob(jobId, request);
      jobRepository.save(job);
      
      LOGGER.info("Created async transcription job: {}", jobId);

      // Start async processing
      processJobAsync(job);

      return ResponseEntity.accepted().body(new AsyncJobResponse(jobId, null));
    } catch (Exception e) {
      LOGGER.error("Failed to start transcription", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }



  /**
   * Process a job asynchronously.
   *
   * <p>This runs in a separate thread pool (configured by Spring's @EnableAsync). The job status is
   * updated as processing progresses.
   */
  @Async
  public void processJobAsync(TranscriptionJob job) {
    LOGGER.info("Starting async processing for job: {}", job.getJobId());

    try {
      job.setStatus(Status.PROCESSING);
      job.setProgress(10);
      jobRepository.save(job);

      // Execute transcription
      TranscriptionResponse result = transcriptionService.transcribe(job.getRequest());

      // Update job with result
      job.setStatus(Status.COMPLETED);
      job.setProgress(100);
      job.setResult(result);
      jobRepository.save(job);

      LOGGER.info("Completed async processing for job: {}", job.getJobId());

    } catch (Exception e) {
      LOGGER.error("Async processing failed for job: {}", job.getJobId(), e);
      job.setStatus(Status.FAILED);
      job.setError(e.getMessage());
      jobRepository.save(job);
    }
  }

  /**
   * Get job status.
   *
   * <p>Returns the current state of an async job. If the job is completed, includes the full
   * transcription result.
   */
  @GetMapping("/api/jobs/{id}")
  @Operation(
      summary = "Get job status",
      description = "Check the status of an async transcription job")
  public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable String id) {
    return jobRepository
        .findById(id)
        .map(
            job ->
                ResponseEntity.ok(
                    new JobStatusResponse(
                        job.getJobId(),
                        job.getStatus(),
                        job.getProgress(),
                        job.getResult(),
                        job.getError(),
                        null)))
        .orElse(ResponseEntity.notFound().build());
  }
}
