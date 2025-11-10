package com.scholary.mp3.handler.api;

import com.scholary.mp3.handler.api.JobStatusResponse.Status;
import com.scholary.mp3.handler.job.JobRepository;
import com.scholary.mp3.handler.job.TranscriptionJob;
import com.scholary.mp3.handler.logging.StructuredLogger;
import com.scholary.mp3.handler.monitoring.KibanaUrlGenerator;
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
  private final KibanaUrlGenerator kibanaUrlGenerator;

  public TranscriptionController(
      com.scholary.mp3.handler.service.TranscriptionService transcriptionService,
      JobRepository jobRepository,
      KibanaUrlGenerator kibanaUrlGenerator) {
    this.transcriptionService = transcriptionService;
    this.jobRepository = jobRepository;
    this.kibanaUrlGenerator = kibanaUrlGenerator;
  }

  /**
   * Preview chunk boundaries without processing.
   *
   * <p>This is useful for understanding how a file will be split before committing to a full
   * transcription. It's fast because it only analyzes silence and plans chunks - no actual cutting
   * or transcription.
   */
  @PostMapping("/chunks/preview")
  @Operation(
      summary = "Preview chunk boundaries",
      description =
          "Analyze an audio file and return planned chunk boundaries without transcribing")
  public ResponseEntity<ChunkPreviewResponse> previewChunks(
      @Valid @RequestBody ChunkPreviewRequest request) {
    String jobId = UUID.randomUUID().toString();
    try {
      StructuredLogger.setJobContext(jobId, request.bucket(), request.key());
      LOGGER.info("Preview chunks request: bucket={}, key={}", request.bucket(), request.key());

      ChunkPreviewResponse response = transcriptionService.previewChunks(request);
      return ResponseEntity.ok(response);
    } catch (IOException e) {
      LOGGER.error("Failed to preview chunks", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    } finally {
      StructuredLogger.clearJobContext();
    }
  }

  /**
   * Transcribe an MP3 file asynchronously.
   *
   * <p>Creates a job, starts processing in the background, and returns the job ID immediately.
   * Client can poll /jobs/{id} to check status and monitor progress in Kibana.
   *
   * <p>This approach is ideal for long-running transcriptions of large files where you want to
   * track progress and avoid HTTP timeouts.
   */
  @PostMapping("/transcribe")
  @Operation(
      summary = "Transcribe audio file (async)",
      description =
          "Process an MP3 file asynchronously: chunk it, transcribe each chunk, merge results, and"
              + " optionally save to storage. Returns job ID immediately for status polling.")
  public ResponseEntity<AsyncJobResponse> transcribe(
      @Valid @RequestBody TranscriptionRequest request) {
    String jobId = UUID.randomUUID().toString();
    TranscriptionJob job = new TranscriptionJob(jobId, request);

    jobRepository.save(job);

    StructuredLogger.setJobContext(jobId, request.bucket(), request.key());
    LOGGER.info("Created async transcription job: {}", jobId);
    StructuredLogger.clearJobContext();

    // Start processing asynchronously
    processJobAsync(job);

    String kibanaUrl = kibanaUrlGenerator.generateJobUrl(jobId);
    return ResponseEntity.accepted().body(new AsyncJobResponse(jobId, kibanaUrl));
  }

  /**
   * Process a job asynchronously.
   *
   * <p>This runs in a separate thread pool (configured by Spring's @EnableAsync). The job status is
   * updated as processing progresses.
   */
  @Async
  public void processJobAsync(TranscriptionJob job) {
    StructuredLogger.setJobContext(
        job.getJobId(), job.getRequest().bucket(), job.getRequest().key());
    LOGGER.info("Starting async processing for job: {}", job.getJobId());

    try {
      job.setStatus(Status.PROCESSING);
      job.setProgress(10);
      jobRepository.save(job);

      // Execute transcription
      TranscriptionResponse result = transcriptionService.transcribeStreaming(job.getRequest());

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
    } finally {
      StructuredLogger.clearJobContext();
    }
  }

  /**
   * Get job status.
   *
   * <p>Returns the current state of an async job. If the job is completed, includes the full
   * transcription result.
   */
  @GetMapping("/jobs/{id}")
  @Operation(
      summary = "Get job status",
      description = "Check the status of an async transcription job")
  public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable String id) {
    return jobRepository
        .findById(id)
        .map(
            job -> {
              String kibanaUrl = kibanaUrlGenerator.generateJobUrl(job.getJobId());
              return ResponseEntity.ok(
                  new JobStatusResponse(
                      job.getJobId(),
                      job.getStatus(),
                      job.getProgress(),
                      job.getResult(),
                      job.getError(),
                      kibanaUrl));
            })
        .orElse(ResponseEntity.notFound().build());
  }
}
