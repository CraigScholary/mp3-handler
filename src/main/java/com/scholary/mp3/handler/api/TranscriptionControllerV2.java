package com.scholary.mp3.handler.api;

import com.scholary.mp3.handler.logging.StructuredLogger;
import com.scholary.mp3.handler.service.TranscriptionOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for MP3 transcription with strategy selection (V2).
 *
 * <p>Provides endpoints for:
 *
 * <ul>
 *   <li>Transcription with mode selection (OVERLAP or SILENCE_AWARE)
 *   <li>Chunk preview
 * </ul>
 */
@RestController
@RequestMapping("/v2")
@Tag(name = "Transcription V2", description = "MP3 transcription API with strategy selection")
public class TranscriptionControllerV2 {

  private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptionControllerV2.class);

  private final TranscriptionOrchestrator orchestrator;

  public TranscriptionControllerV2(TranscriptionOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  /**
   * Transcribe an MP3 file using the specified strategy.
   *
   * <p>Supports two modes:
   *
   * <ul>
   *   <li>OVERLAP: Fixed-interval chunking with 30s overlaps and word matching
   *   <li>SILENCE_AWARE: Silence-based chunking with simple concatenation
   * </ul>
   */
  @PostMapping("/transcribe")
  @Operation(
      summary = "Transcribe audio file with strategy selection",
      description =
          "Process an MP3 file using either overlap-based or silence-aware chunking. "
              + "Overlap mode uses word matching for accurate merging. "
              + "Silence-aware mode splits at natural pauses.")
  public ResponseEntity<TranscriptionResponse> transcribe(
      @Valid @RequestBody TranscriptionRequestV2 request) {
    String jobId = UUID.randomUUID().toString();
    try {
      StructuredLogger.setJobContext(jobId, request.bucket(), request.key());
      LOGGER.info(
          "Transcribe request: bucket={}, key={}, mode={}",
          request.bucket(),
          request.key(),
          request.mode());

      TranscriptionResponse response = orchestrator.transcribe(request);
      return ResponseEntity.ok(response);

    } catch (IOException e) {
      LOGGER.error("Failed to transcribe", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    } finally {
      StructuredLogger.clearJobContext();
    }
  }
}
