package com.scholary.mp3.handler.service;

import com.scholary.mp3.handler.api.ChunkingMode;
import com.scholary.mp3.handler.api.TranscriptionRequestV2;
import com.scholary.mp3.handler.api.TranscriptionResponse;
import com.scholary.mp3.handler.cache.ChunkCache;
import com.scholary.mp3.handler.logging.StructuredLogger;
import com.scholary.mp3.handler.objectstore.ObjectStoreClient;
import com.scholary.mp3.handler.objectstore.ObjectStoreClient.ObjectMetadata;
import com.scholary.mp3.handler.strategy.ChunkPlan;
import com.scholary.mp3.handler.strategy.ChunkingContext;
import com.scholary.mp3.handler.strategy.ChunkingStrategy;
import com.scholary.mp3.handler.strategy.MergeStrategy;
import com.scholary.mp3.handler.strategy.OverlapChunkingStrategy;
import com.scholary.mp3.handler.strategy.SilenceAwareChunkingStrategy;
import com.scholary.mp3.handler.transcript.ChunkTranscript;
import com.scholary.mp3.handler.transcript.ConcatenationMerger;
import com.scholary.mp3.handler.transcript.MergedSegment;
import com.scholary.mp3.handler.transcript.WordMatchingMerger;
import com.scholary.mp3.handler.whisper.WhisperResponse;
import com.scholary.mp3.handler.whisper.WhisperService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates transcription using pluggable strategies.
 *
 * <p>Supports two modes:
 *
 * <ul>
 *   <li>OVERLAP: Fixed-interval chunking with word matching merge
 *   <li>SILENCE_AWARE: Silence-based chunking with concatenation merge
 * </ul>
 */
@Service
public class TranscriptionOrchestrator {

  private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptionOrchestrator.class);
  private final StructuredLogger structuredLogger = new StructuredLogger(LOGGER);

  private static final long BYTES_PER_SECOND_ESTIMATE = 16000;

  private final ObjectStoreClient objectStoreClient;
  private final WhisperService whisperService;
  private final TranscriptWriter transcriptWriter;
  private final ChunkCache chunkCache;

  // Strategies
  private final Map<ChunkingMode, ChunkingStrategy> chunkingStrategies;
  private final Map<ChunkingMode, MergeStrategy> mergeStrategies;

  private final Path tempDir;
  private final int maxFileDurationHours;

  public TranscriptionOrchestrator(
      ObjectStoreClient objectStoreClient,
      WhisperService whisperService,
      TranscriptWriter transcriptWriter,
      ChunkCache chunkCache,
      OverlapChunkingStrategy overlapStrategy,
      SilenceAwareChunkingStrategy silenceStrategy,
      WordMatchingMerger wordMatchingMerger,
      ConcatenationMerger concatenationMerger,
      @Value("${transcription.tempDir}") String tempDir,
      @Value("${transcription.maxFileDurationHours}") int maxFileDurationHours) {

    this.objectStoreClient = objectStoreClient;
    this.whisperService = whisperService;
    this.transcriptWriter = transcriptWriter;
    this.chunkCache = chunkCache;
    this.tempDir = Paths.get(tempDir);
    this.maxFileDurationHours = maxFileDurationHours;

    // Wire up strategies
    this.chunkingStrategies = new HashMap<>();
    this.chunkingStrategies.put(ChunkingMode.OVERLAP, overlapStrategy);
    this.chunkingStrategies.put(ChunkingMode.SILENCE_AWARE, silenceStrategy);

    this.mergeStrategies = new HashMap<>();
    this.mergeStrategies.put(ChunkingMode.OVERLAP, wordMatchingMerger);
    this.mergeStrategies.put(ChunkingMode.SILENCE_AWARE, concatenationMerger);

    try {
      Files.createDirectories(this.tempDir);
    } catch (IOException e) {
      throw new RuntimeException("Failed to create temp directory: " + tempDir, e);
    }
  }

  /**
   * Transcribe a file using the specified strategy.
   *
   * @param request the transcription request
   * @return the transcription response
   * @throws IOException if processing fails
   */
  public TranscriptionResponse transcribe(TranscriptionRequestV2 request) throws IOException {
    String correlationId = UUID.randomUUID().toString();
    MDC.put("correlationId", correlationId);

    try {
      LOGGER.info(
          "Starting transcription: bucket={}, key={}, mode={}",
          request.bucket(),
          request.key(),
          request.mode());

      // Get file metadata
      ObjectMetadata metadata =
          objectStoreClient.getObjectMetadata(request.bucket(), request.key());
      LOGGER.info(
          "File size: {} bytes ({} MB)",
          metadata.contentLength(),
          metadata.contentLength() / 1024 / 1024);

      // Estimate duration
      Duration estimatedDuration =
          Duration.ofSeconds(metadata.contentLength() / BYTES_PER_SECOND_ESTIMATE);
      LOGGER.info(
          "Estimated duration: {} seconds ({} hours)",
          estimatedDuration.getSeconds(),
          estimatedDuration.toHours());

      validateDuration(estimatedDuration);

      // Phase 1: Plan chunks using selected strategy
      ChunkingStrategy chunkingStrategy = chunkingStrategies.get(request.mode());
      ChunkingContext context =
          new ChunkingContext(
              request.bucket(),
              request.key(),
              metadata.contentLength(),
              estimatedDuration.getSeconds(),
              request.chunkingConfig());

      List<ChunkPlan> chunkPlans = chunkingStrategy.planChunks(context);
      LOGGER.info(
          "Planned {} chunks using {} strategy", chunkPlans.size(), chunkingStrategy.getStrategyName());

      // Phase 2: Process chunks
      List<ChunkTranscript> chunkTranscripts = processChunks(request, chunkPlans);

      // Phase 3: Merge using selected strategy
      MergeStrategy mergeStrategy = mergeStrategies.get(request.mode());
      List<MergedSegment> mergedSegments = mergeStrategy.merge(chunkTranscripts, chunkPlans);
      LOGGER.info(
          "Merged {} chunks using {} strategy into {} segments",
          chunkTranscripts.size(),
          mergeStrategy.getStrategyName(),
          mergedSegments.size());

      // Build response
      String language = chunkTranscripts.isEmpty() ? "unknown" : chunkTranscripts.get(0).language();
      TranscriptionResponse.Diagnostics diagnostics =
          buildDiagnostics(chunkPlans, chunkTranscripts, estimatedDuration);

      TranscriptionResponse.StorageInfo storageInfo = null;
      if (request.save()) {
        storageInfo = saveTranscripts(request.bucket(), request.key(), mergedSegments, language);
      }

      LOGGER.info("Transcription completed: {} segments", mergedSegments.size());

      return new TranscriptionResponse(
          correlationId, mergedSegments, language, diagnostics, storageInfo);

    } finally {
      MDC.remove("correlationId");
    }
  }

  /**
   * Process all chunks.
   */
  private List<ChunkTranscript> processChunks(TranscriptionRequestV2 request, List<ChunkPlan> plans)
      throws IOException {

    List<ChunkTranscript> transcripts = new ArrayList<>();
    int cachedChunks = 0;

    for (ChunkPlan plan : plans) {
      LOGGER.info(
          "Processing chunk {}/{}: {}s-{}s",
          plan.chunkIndex() + 1,
          plans.size(),
          plan.startSeconds(),
          plan.endSeconds());

      // Check cache
      String cacheKey =
          ChunkCache.generateKey(
              request.bucket(), request.key(), plan.chunkIndex(), plan.startSeconds(), plan.endSeconds());

      boolean wasCached = chunkCache.get(cacheKey).isPresent();

      ChunkTranscript transcript =
          chunkCache
              .get(cacheKey)
              .orElseGet(
                  () -> {
                    try {
                      ChunkTranscript result = processChunk(request, plan);
                      chunkCache.put(cacheKey, result);
                      return result;
                    } catch (IOException e) {
                      throw new RuntimeException("Failed to process chunk " + plan.chunkIndex(), e);
                    }
                  });

      if (wasCached) {
        cachedChunks++;
        LOGGER.info("Chunk {}/{} loaded from cache", plan.chunkIndex() + 1, plans.size());
      }

      transcripts.add(transcript);

      int progress = ((plan.chunkIndex() + 1) * 100) / plans.size();
      LOGGER.info(
          "Progress: {}% ({}/{} chunks, {} cached)",
          progress,
          plan.chunkIndex() + 1,
          plans.size(),
          cachedChunks);
    }

    if (cachedChunks > 0) {
      LOGGER.info("Resumed from cache: {}/{} chunks were cached", cachedChunks, plans.size());
    }

    return transcripts;
  }

  /**
   * Process a single chunk.
   */
  private ChunkTranscript processChunk(TranscriptionRequestV2 request, ChunkPlan plan)
      throws IOException {

    structuredLogger.logChunkStarted(
        plan.chunkIndex(), plan.startSeconds(), plan.endSeconds(), plan.duration());
    long startTime = System.currentTimeMillis();

    // Calculate byte range
    long startByte = (long) (plan.startSeconds() * BYTES_PER_SECOND_ESTIMATE);
    long endByte = (long) (plan.endSeconds() * BYTES_PER_SECOND_ESTIMATE);

    // Add buffer
    long bufferBytes = BYTES_PER_SECOND_ESTIMATE;
    startByte = Math.max(0, startByte - bufferBytes);

    LOGGER.debug(
        "Downloading byte range for chunk {}: {}-{} ({} KB)",
        plan.chunkIndex(),
        startByte,
        endByte,
        (endByte - startByte) / 1024);

    // Download chunk
    Path chunkFile =
        tempDir.resolve(String.format("chunk_%d_%s.mp3", plan.chunkIndex(), UUID.randomUUID()));

    try (InputStream rangeStream =
        objectStoreClient.getObjectRange(request.bucket(), request.key(), startByte, endByte)) {
      Files.copy(rangeStream, chunkFile, StandardCopyOption.REPLACE_EXISTING);
    }

    try {
      // Transcribe
      WhisperResponse response =
          whisperService.transcribe(chunkFile, plan.duration(), plan.chunkIndex());

      long transcribeMs = System.currentTimeMillis() - startTime;
      structuredLogger.logChunkFinished(
          plan.chunkIndex(), plan.startSeconds(), plan.endSeconds(), plan.duration(), transcribeMs);

      return new ChunkTranscript(
          plan.chunkIndex(), plan.startSeconds(), response.segments(), response.language());

    } finally {
      Files.deleteIfExists(chunkFile);
    }
  }

  private void validateDuration(Duration duration) {
    long maxSeconds = maxFileDurationHours * 3600L;
    if (duration.getSeconds() > maxSeconds) {
      throw new IllegalArgumentException(
          String.format(
              "Audio file too long: %d seconds (max: %d seconds)",
              duration.getSeconds(), maxSeconds));
    }
  }

  private TranscriptionResponse.Diagnostics buildDiagnostics(
      List<ChunkPlan> chunkPlans, List<ChunkTranscript> chunkTranscripts, Duration totalDuration) {

    List<TranscriptionResponse.ChunkInfo> chunkInfos = new ArrayList<>();
    for (int i = 0; i < chunkPlans.size(); i++) {
      ChunkPlan plan = chunkPlans.get(i);
      int segmentCount =
          i < chunkTranscripts.size() ? chunkTranscripts.get(i).segments().size() : 0;
      chunkInfos.add(
          new TranscriptionResponse.ChunkInfo(
              i, plan.startSeconds(), plan.endSeconds(), segmentCount));
    }

    int totalSegments = chunkTranscripts.stream().mapToInt(ct -> ct.segments().size()).sum();

    return new TranscriptionResponse.Diagnostics(
        chunkPlans.size(), totalDuration.toMillis() / 1000.0, totalSegments, chunkInfos);
  }

  private TranscriptionResponse.StorageInfo saveTranscripts(
      String bucket, String originalKey, List<MergedSegment> segments, String language)
      throws IOException {
    return transcriptWriter.saveTranscripts(bucket, originalKey, segments, language);
  }
}
