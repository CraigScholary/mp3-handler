package com.scholary.mp3.handler.logging;

import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Utility for structured logging with MDC (Mapped Diagnostic Context).
 *
 * <p>Provides methods to log events with structured fields that can be queried in Kibana.
 */
public class StructuredLogger {

  private final Logger logger;

  public StructuredLogger(Logger logger) {
    this.logger = logger;
  }

  /** Log chunk planning event. */
  public void logChunkPlanned(
      int chunkIndex,
      double start,
      double end,
      double overlapStart,
      double overlapEnd,
      boolean nudged,
      double nudgeDelta) {
    try {
      MDC.put("event_type", "chunk_planned");
      MDC.put("chunk_index", String.valueOf(chunkIndex));
      MDC.put("start", String.valueOf(start));
      MDC.put("end", String.valueOf(end));
      MDC.put("overlapStart", String.valueOf(overlapStart));
      MDC.put("overlapEnd", String.valueOf(overlapEnd));
      MDC.put("nudged", String.valueOf(nudged));
      MDC.put("nudgeDelta", String.valueOf(nudgeDelta));

      logger.debug(
          "Chunk planned: index={}, range=[{}-{}], overlap=[{}-{}], nudged={}, delta={}s",
          chunkIndex,
          start,
          end,
          overlapStart,
          overlapEnd,
          nudged,
          nudgeDelta);
    } finally {
      clearEventFields();
    }
  }

  /** Log chunk started event. */
  public void logChunkStarted(int chunkIndex, double start, double end, double durationSeconds) {
    try {
      MDC.put("event_type", "chunk_started");
      MDC.put("chunk_index", String.valueOf(chunkIndex));
      MDC.put("start", String.valueOf(start));
      MDC.put("end", String.valueOf(end));
      MDC.put("durationSeconds", String.valueOf(durationSeconds));

      logger.debug(
          "Chunk started: index={}, range=[{}-{}], duration={}s",
          chunkIndex,
          start,
          end,
          durationSeconds);
    } finally {
      clearEventFields();
    }
  }

  /** Log chunk finished event. */
  public void logChunkFinished(
      int chunkIndex, double start, double end, double durationSeconds, long transcribeMs) {
    try {
      MDC.put("event_type", "chunk_finished");
      MDC.put("chunk_index", String.valueOf(chunkIndex));
      MDC.put("start", String.valueOf(start));
      MDC.put("end", String.valueOf(end));
      MDC.put("durationSeconds", String.valueOf(durationSeconds));
      MDC.put("transcribeMs", String.valueOf(transcribeMs));

      logger.debug(
          "Chunk finished: index={}, range=[{}-{}], duration={}s, transcribe={}ms",
          chunkIndex,
          start,
          end,
          durationSeconds,
          transcribeMs);
    } finally {
      clearEventFields();
    }
  }

  /** Log overlap merge event. */
  public void logOverlapMerge(
      int leftChunk,
      int rightChunk,
      double overlapSeconds,
      boolean dupRemoved,
      int overlapCharsRemoved) {
    try {
      MDC.put("event_type", "overlap_merge");
      MDC.put("leftChunk", String.valueOf(leftChunk));
      MDC.put("rightChunk", String.valueOf(rightChunk));
      MDC.put("overlapSeconds", String.valueOf(overlapSeconds));
      MDC.put("dupRemoved", String.valueOf(dupRemoved));
      MDC.put("overlapCharsRemoved", String.valueOf(overlapCharsRemoved));

      logger.debug(
          "Overlap merge: chunks=[{},{}], overlap={}s, dupRemoved={}, charsRemoved={}",
          leftChunk,
          rightChunk,
          overlapSeconds,
          dupRemoved,
          overlapCharsRemoved);
    } finally {
      clearEventFields();
    }
  }

  /** Log transcription retry event. */
  public void logTranscribeRetry(
      int chunkIndex, int attempt, int maxRetries, String errorType, String message) {
    try {
      MDC.put("event_type", "transcribe_retry");
      MDC.put("chunk_index", String.valueOf(chunkIndex));
      MDC.put("attempt", String.valueOf(attempt));
      MDC.put("maxRetries", String.valueOf(maxRetries));
      MDC.put("errorType", errorType);

      logger.warn(
          "Transcribe retry: chunk={}, attempt={}/{}, error={}, message={}",
          chunkIndex,
          attempt,
          maxRetries,
          errorType,
          message);
    } finally {
      clearEventFields();
    }
  }

  /** Log transcription failure event. */
  public void logTranscribeFailed(
      int chunkIndex, int maxRetries, String errorType, String message) {
    try {
      MDC.put("event_type", "transcribe_failed");
      MDC.put("chunk_index", String.valueOf(chunkIndex));
      MDC.put("maxRetries", String.valueOf(maxRetries));
      MDC.put("errorType", errorType);

      logger.error(
          "Transcribe failed: chunk={}, maxRetries={}, error={}, message={}",
          chunkIndex,
          maxRetries,
          errorType,
          message);
    } finally {
      clearEventFields();
    }
  }

  /** Log job progress event. */
  public void logJobProgress(
      String jobId, int chunksProcessed, int totalChunks, int percentComplete, String phase) {
    try {
      MDC.put("event_type", "job_progress");
      MDC.put("jobId", jobId);
      MDC.put("chunksProcessed", String.valueOf(chunksProcessed));
      MDC.put("totalChunks", String.valueOf(totalChunks));
      MDC.put("percentComplete", String.valueOf(percentComplete));
      MDC.put("phase", phase);

      logger.info(
          "Job progress: jobId={}, phase={}, chunks={}/{}, progress={}%",
          jobId,
          phase,
          chunksProcessed,
          totalChunks,
          percentComplete);
    } finally {
      clearEventFields();
    }
  }

  /** Log silence analysis progress. */
  public void logSilenceAnalysisProgress(
      int chunksAnalyzed, int totalChunks, int silencesFound) {
    try {
      MDC.put("event_type", "silence_analysis_progress");
      MDC.put("chunksAnalyzed", String.valueOf(chunksAnalyzed));
      MDC.put("totalChunks", String.valueOf(totalChunks));
      MDC.put("silencesFound", String.valueOf(silencesFound));

      logger.info(
          "Silence analysis progress: chunks={}/{}, silences={}",
          chunksAnalyzed,
          totalChunks,
          silencesFound);
    } finally {
      clearEventFields();
    }
  }

  /** Set job context in MDC. */
  public static void setJobContext(String jobId, String bucket, String key) {
    MDC.put("jobId", jobId);
    MDC.put("bucket", bucket);
    MDC.put("key", key);
  }

  /** Clear job context from MDC. */
  public static void clearJobContext() {
    MDC.remove("jobId");
    MDC.remove("bucket");
    MDC.remove("key");
  }

  /** Clear event-specific fields from MDC. */
  private void clearEventFields() {
    MDC.remove("event_type");
    MDC.remove("chunk_index");
    MDC.remove("start");
    MDC.remove("end");
    MDC.remove("overlapStart");
    MDC.remove("overlapEnd");
    MDC.remove("nudged");
    MDC.remove("nudgeDelta");
    MDC.remove("durationSeconds");
    MDC.remove("transcribeMs");
    MDC.remove("leftChunk");
    MDC.remove("rightChunk");
    MDC.remove("overlapSeconds");
    MDC.remove("dupRemoved");
    MDC.remove("overlapCharsRemoved");
    MDC.remove("attempt");
    MDC.remove("maxRetries");
    MDC.remove("errorType");
  }
}
