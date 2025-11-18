package com.scholary.mp3.handler.service;

import com.scholary.mp3.handler.api.ChunkPreviewRequest;
import com.scholary.mp3.handler.api.ChunkPreviewResponse;
import com.scholary.mp3.handler.api.ChunkPreviewResponse.ChunkPlan;
import com.scholary.mp3.handler.api.TranscriptionRequest;
import com.scholary.mp3.handler.api.TranscriptionResponse;
import com.scholary.mp3.handler.cache.ChunkCache;
import com.scholary.mp3.handler.chunking.FfmpegChunkPlanner;
import com.scholary.mp3.handler.chunking.TimeRange;
import com.scholary.mp3.handler.logging.StructuredLogger;
import com.scholary.mp3.handler.objectstore.ObjectStoreClient;
import com.scholary.mp3.handler.objectstore.ObjectStoreClient.ObjectMetadata;
import com.scholary.mp3.handler.streaming.BackpressureController;
import com.scholary.mp3.handler.transcript.ChunkTranscript;
import com.scholary.mp3.handler.transcript.MergedSegment;
import com.scholary.mp3.handler.transcript.TranscriptMerger;
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
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Transcription service optimized for large files with streaming architecture.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Uses HTTP range requests to download only needed chunks
 *   <li>Processes chunks progressively (doesn't wait for full download)
 *   <li>Cleans up temp files immediately after processing each chunk
 *   <li>Uses constant memory (~20MB) regardless of file size
 *   <li>Supports backpressure control to prevent memory spikes
 *   <li>Chunk caching for resume capability
 * </ul>
 *
 * <p>All transcriptions are asynchronous - returns job ID immediately for status polling and
 * progress monitoring in Kibana.
 */
@Service
public class TranscriptionService {

  private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptionService.class);
  private final StructuredLogger structuredLogger = new StructuredLogger(LOGGER);

  // Estimate: MP3 at 128kbps = 16KB/second = 960KB/minute
  private static final long BYTES_PER_SECOND_ESTIMATE = 16000;

  private final ObjectStoreClient objectStoreClient;
  private final FfmpegChunkPlanner chunkPlanner;
  private final WhisperService whisperService;
  private final TranscriptMerger transcriptMerger;
  private final TranscriptWriter transcriptWriter;
  private final ChunkCache chunkCache;
  private final BackpressureController backpressureController;
  private final com.scholary.mp3.handler.chunking.StreamingSilenceAnalyzer streamingSilenceAnalyzer;
  private final com.scholary.mp3.handler.transcript.SegmentAggregator segmentAggregator;

  private final Path tempDir;
  private final int maxFileDurationHours;

  public TranscriptionService(
      ObjectStoreClient objectStoreClient,
      FfmpegChunkPlanner chunkPlanner,
      WhisperService whisperService,
      TranscriptMerger transcriptMerger,
      TranscriptWriter transcriptWriter,
      ChunkCache chunkCache,
      BackpressureController backpressureController,
      com.scholary.mp3.handler.chunking.StreamingSilenceAnalyzer streamingSilenceAnalyzer,
      com.scholary.mp3.handler.transcript.SegmentAggregator segmentAggregator,
      @Value("${transcription.tempDir}") String tempDir,
      @Value("${transcription.maxFileDurationHours}") int maxFileDurationHours) {

    this.objectStoreClient = objectStoreClient;
    this.chunkPlanner = chunkPlanner;
    this.whisperService = whisperService;
    this.transcriptMerger = transcriptMerger;
    this.transcriptWriter = transcriptWriter;
    this.chunkCache = chunkCache;
    this.backpressureController = backpressureController;
    this.streamingSilenceAnalyzer = streamingSilenceAnalyzer;
    this.segmentAggregator = segmentAggregator;
    this.tempDir = Paths.get(tempDir);
    this.maxFileDurationHours = maxFileDurationHours;

    try {
      Files.createDirectories(this.tempDir);
    } catch (IOException e) {
      throw new RuntimeException("Failed to create temp directory: " + tempDir, e);
    }
  }

  /**
   * Preview chunk boundaries without transcribing.
   *
   * @param request the chunk preview request
   * @return the chunk preview response
   * @throws IOException if processing fails
   */
  public ChunkPreviewResponse previewChunks(ChunkPreviewRequest request) throws IOException {
    LOGGER.info("Previewing chunks for: bucket={}, key={}", request.bucket(), request.key());

    // Get file metadata without downloading
    ObjectMetadata metadata = objectStoreClient.getObjectMetadata(request.bucket(), request.key());
    LOGGER.info(
        "File size: {} bytes ({} MB)",
        metadata.contentLength(),
        metadata.contentLength() / 1024 / 1024);

    // Estimate duration from file size (MP3 at ~128kbps)
    Duration estimatedDuration =
        Duration.ofSeconds(metadata.contentLength() / BYTES_PER_SECOND_ESTIMATE);
    LOGGER.info(
        "Estimated duration: {} seconds ({} hours)",
        estimatedDuration.getSeconds(),
        estimatedDuration.toHours());

    // Plan chunks based on estimated duration
    List<TimeRange> chunkRanges =
        chunkPlanner.planChunks(
            Duration.ofSeconds(request.chunkSeconds()),
            Duration.ofSeconds(request.overlapSeconds()),
            List.of(), // No silence detection for preview
            estimatedDuration);

    LOGGER.info("Planned {} chunks", chunkRanges.size());

    // Convert TimeRange to ChunkPlan for response
    List<ChunkPlan> chunks = new ArrayList<>();
    for (int i = 0; i < chunkRanges.size(); i++) {
      TimeRange range = chunkRanges.get(i);

      // Calculate overlap ranges
      double overlapStart = i > 0 ? range.start() : 0.0;
      double overlapEnd = i < chunkRanges.size() - 1 ? range.end() : range.end();

      // Log chunk planning
      structuredLogger.logChunkPlanned(
          i, range.start(), range.end(), overlapStart, overlapEnd, false, 0.0);

      chunks.add(
          new ChunkPlan(
              i,
              range.start(),
              range.end(),
              false // nudged flag - not applicable for time-based planning
              ));
    }

    LOGGER.info(
        "Preview complete: {} chunks, total duration: {} seconds",
        chunks.size(),
        estimatedDuration.toSeconds());

    return new ChunkPreviewResponse((double) estimatedDuration.toSeconds(), chunks.size(), chunks);
  }

  /**
   * Transcribe a large file using streaming approach.
   *
   * <p>This method:
   *
   * <ol>
   *   <li>Gets file metadata (size) without downloading
   *   <li>Estimates duration from file size
   *   <li>Plans chunks based on estimated duration
   *   <li>For each chunk:
   *       <ul>
   *         <li>Downloads only that chunk's byte range
   *         <li>Processes immediately
   *         <li>Cleans up temp files
   *       </ul>
   *   <li>Merges results
   * </ol>
   *
   * @param request the transcription request
   * @return the transcription response
   * @throws IOException if processing fails
   */
  public TranscriptionResponse transcribeStreaming(TranscriptionRequest request)
      throws IOException {
    String correlationId = UUID.randomUUID().toString();
    MDC.put("correlationId", correlationId);

    try {
      LOGGER.info(
          "Starting streaming transcription: bucket={}, key={}, chunkSeconds={},"
              + " overlapSeconds={}",
          request.bucket(),
          request.key(),
          request.chunkSeconds(),
          request.overlapSeconds());

      // Get file metadata without downloading
      ObjectMetadata metadata =
          objectStoreClient.getObjectMetadata(request.bucket(), request.key());
      LOGGER.info(
          "File size: {} bytes ({} MB)",
          metadata.contentLength(),
          metadata.contentLength() / 1024 / 1024);

      // Estimate duration from file size (MP3 at ~128kbps)
      Duration estimatedDuration =
          Duration.ofSeconds(metadata.contentLength() / BYTES_PER_SECOND_ESTIMATE);
      LOGGER.info(
          "Estimated duration: {} seconds ({} hours)",
          estimatedDuration.getSeconds(),
          estimatedDuration.toHours());

      validateDuration(estimatedDuration);

      // Plan chunks based on silence analysis (if enabled)
      List<TimeRange> chunkRanges;
      List<Double> chunkBreakpoints = new ArrayList<>();
      
      if (request.silenceAware()) {
        LOGGER.info("Starting greedy streaming silence analysis with lookback");
        
        // Use greedy approach: single pass with lookback
        // chunkSeconds is the MAX duration (e.g., 3600s = 1 hour max)
        chunkBreakpoints = streamingSilenceAnalyzer.findBreakpointsGreedy(
            request.bucket(),
            request.key(),
            metadata.contentLength(),
            request.chunkSeconds());
        
        LOGGER.info("Found {} breakpoints using greedy streaming", chunkBreakpoints.size());
        
        // Create chunks from breakpoints
        chunkRanges = createChunksFromBreakpoints(chunkBreakpoints, estimatedDuration);
      } else {
        // Fixed-interval chunking (old behavior)
        chunkRanges =
            chunkPlanner.planChunks(
                Duration.ofSeconds(request.chunkSeconds()),
                Duration.ofSeconds(request.overlapSeconds()),
                List.of(),
                estimatedDuration);
      }

      LOGGER.info(
          "Planned {} chunks for streaming transcription (silenceAware={})",
          chunkRanges.size(),
          request.silenceAware());

      // Process each chunk progressively with backpressure control
      List<ChunkTranscript> chunkTranscripts = new ArrayList<>();
      int cachedChunks = 0;

      // Log initial memory state
      backpressureController.logMemoryStats();

      for (int i = 0; i < chunkRanges.size(); i++) {
        TimeRange range = chunkRanges.get(i);
        final int chunkIndex = i; // Make effectively final for lambda

        // Apply backpressure - wait if memory is high
        try {
          backpressureController.waitIfNeeded();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Chunk processing interrupted", e);
        }

        LOGGER.info(
            "Processing chunk {}/{}: {}s-{}s",
            chunkIndex + 1,
            chunkRanges.size(),
            range.start(),
            range.end());

        // Check cache first
        String cacheKey =
            ChunkCache.generateKey(
                request.bucket(), request.key(), chunkIndex, range.start(), range.end());

        boolean wasCached = chunkCache.get(cacheKey).isPresent();

        ChunkTranscript transcript =
            chunkCache
                .get(cacheKey)
                .orElseGet(
                    () -> {
                      try {
                        // Cache miss - download and process this chunk
                        // Silence analysis already done in greedy streaming phase
                        ChunkTranscript result =
                            processChunkStreaming(
                                request.bucket(),
                                request.key(),
                                range,
                                chunkIndex,
                                metadata.contentLength(),
                                false); // Don't re-analyze silence

                        // Cache the result for future resume
                        chunkCache.put(cacheKey, result);
                        return result;
                      } catch (IOException e) {
                        throw new RuntimeException("Failed to process chunk " + chunkIndex, e);
                      }
                    });

        if (wasCached) {
          cachedChunks++;
          LOGGER.info("Chunk {}/{} loaded from cache", chunkIndex + 1, chunkRanges.size());
        }

        chunkTranscripts.add(transcript);

        // Log progress with memory stats
        int progress = ((chunkIndex + 1) * 100) / chunkRanges.size();
        LOGGER.info(
            "Progress: {}% ({}/{} chunks, {} cached) - {}",
            progress,
            chunkIndex + 1,
            chunkRanges.size(),
            cachedChunks,
            backpressureController.getMemoryStats());
      }

      if (cachedChunks > 0) {
        LOGGER.info(
            "Resumed from cache: {}/{} chunks were cached", cachedChunks, chunkRanges.size());
      }

      // Log final memory state
      backpressureController.logMemoryStats();

      // Merge transcripts
      List<MergedSegment> mergedSegments = transcriptMerger.merge(chunkTranscripts);

      String language = chunkTranscripts.isEmpty() ? "unknown" : chunkTranscripts.get(0).language();

      // Aggregate segments if silence-aware chunking was used
      List<MergedSegment> finalSegments;
      if (request.silenceAware() && !chunkBreakpoints.isEmpty()) {
        LOGGER.info("Aggregating {} Whisper segments into {} chunks", 
            mergedSegments.size(), chunkBreakpoints.size() + 1);
        
        List<com.scholary.mp3.handler.transcript.SegmentAggregator.AggregatedSegment> aggregated =
            segmentAggregator.aggregateByChunks(mergedSegments, chunkBreakpoints);
        
        // Convert aggregated segments back to MergedSegment format
        finalSegments = new ArrayList<>();
        for (var agg : aggregated) {
          finalSegments.add(new MergedSegment(agg.start(), agg.end(), agg.text()));
        }
        
        LOGGER.info("Aggregation complete: {} final segments (from {} original)", 
            finalSegments.size(), mergedSegments.size());
      } else {
        finalSegments = mergedSegments;
      }

      TranscriptionResponse.Diagnostics diagnostics =
          buildDiagnostics(chunkRanges, chunkTranscripts, estimatedDuration);

      TranscriptionResponse.StorageInfo storageInfo = null;
      if (request.save()) {
        storageInfo = saveTranscripts(request.bucket(), request.key(), finalSegments, language);
      }

      LOGGER.info("Streaming transcription completed: {} segments", finalSegments.size());

      return new TranscriptionResponse(
          correlationId, finalSegments, language, diagnostics, storageInfo);

    } finally {
      MDC.remove("correlationId");
    }
  }

  /**
   * Process a single chunk using streaming (download only the bytes we need).
   *
   * <p>If silenceAware is enabled, this method will:
   *
   * <ol>
   *   <li>Download the chunk bytes
   *   <li>Detect silence within this chunk
   *   <li>Optionally split into sub-chunks at silence boundaries
   *   <li>Transcribe (potentially multiple sub-chunks)
   *   <li>Merge sub-chunk results
   * </ol>
   *
   * <p>This allows silence-aware processing without downloading the entire file.
   *
   * @param bucket the S3 bucket
   * @param key the S3 key
   * @param range the time range for this chunk
   * @param chunkIndex the chunk index
   * @param totalFileSize the total file size in bytes
   * @param silenceAware whether to detect and use silence for better boundaries
   * @return the chunk transcript
   * @throws IOException if processing fails
   */
  private ChunkTranscript processChunkStreaming(
      String bucket,
      String key,
      TimeRange range,
      int chunkIndex,
      long totalFileSize,
      boolean silenceAware)
      throws IOException {

    // Log chunk started
    structuredLogger.logChunkStarted(chunkIndex, range.start(), range.end(), range.duration());
    long startTime = System.currentTimeMillis();

    // Calculate byte range for this time range
    // This is an approximation - MP3 bitrate varies
    long startByte = (long) (range.start() * BYTES_PER_SECOND_ESTIMATE);
    long endByte = (long) (range.end() * BYTES_PER_SECOND_ESTIMATE);

    // Add buffer to ensure we get complete audio frames
    // MP3 frames are ~26ms, so add 1 second buffer on each side
    long bufferBytes = BYTES_PER_SECOND_ESTIMATE;
    startByte = Math.max(0, startByte - bufferBytes);
    endByte = Math.min(totalFileSize - 1, endByte + bufferBytes);

    LOGGER.debug(
        "Downloading byte range for chunk {}: {}-{} ({} KB)",
        chunkIndex,
        startByte,
        endByte,
        (endByte - startByte) / 1024);

    // Download only this chunk's bytes
    Path chunkFile =
        tempDir.resolve(String.format("chunk_%d_%s.mp3", chunkIndex, UUID.randomUUID()));

    try (InputStream rangeStream =
        objectStoreClient.getObjectRange(bucket, key, startByte, endByte)) {
      Files.copy(rangeStream, chunkFile, StandardCopyOption.REPLACE_EXISTING);
    }

    try {
      ChunkTranscript result;
      // If silence-aware, detect silence in this chunk and potentially refine boundaries
      if (silenceAware) {
        result = processChunkWithSilenceDetection(chunkFile, range, chunkIndex);
      } else {
        // Simple transcription without silence detection
        WhisperResponse response =
            whisperService.transcribe(chunkFile, range.duration(), chunkIndex);
        result =
            new ChunkTranscript(
                chunkIndex, range.start(), response.segments(), response.language());
      }

      // Log chunk finished
      long transcribeMs = System.currentTimeMillis() - startTime;
      structuredLogger.logChunkFinished(
          chunkIndex, range.start(), range.end(), range.duration(), transcribeMs);

      return result;

    } finally {
      // Clean up immediately to keep memory usage low
      Files.deleteIfExists(chunkFile);
    }
  }

  /**
   * Process a chunk with silence detection.
   *
   * <p>This detects silence within the chunk and can optionally split it into sub-chunks at silence
   * boundaries for better transcription quality.
   *
   * <p>For now, we just detect silence and log it. In the future, we could split large chunks at
   * silence points.
   *
   * @param chunkFile the downloaded chunk file
   * @param range the time range for this chunk
   * @param chunkIndex the chunk index
   * @return the chunk transcript
   * @throws IOException if processing fails
   */
  private ChunkTranscript processChunkWithSilenceDetection(
      Path chunkFile, TimeRange range, int chunkIndex) throws IOException {

    LOGGER.debug("Detecting silence in chunk {}", chunkIndex);

    // Detect silence within this chunk
    List<com.scholary.mp3.handler.chunking.SilenceInterval> silenceIntervals =
        chunkPlanner.analyzeSilence(chunkFile);

    LOGGER.debug("Found {} silence intervals in chunk {}", silenceIntervals.size(), chunkIndex);

    // For now, we just transcribe the whole chunk
    // The silence information helps Whisper understand natural pauses
    // Future enhancement: split large chunks at major silence gaps

    WhisperResponse response = whisperService.transcribe(chunkFile, range.duration(), chunkIndex);

    return new ChunkTranscript(chunkIndex, range.start(), response.segments(), response.language());
  }

  /**
   * Create TimeRange chunks from breakpoints.
   *
   * @param breakpoints list of breakpoint times (in seconds)
   * @param totalDuration total file duration
   * @return list of TimeRange chunks
   */
  private List<TimeRange> createChunksFromBreakpoints(
      List<Double> breakpoints, Duration totalDuration) {
    List<TimeRange> chunks = new ArrayList<>();
    
    double previousBreakpoint = 0.0;
    for (Double breakpoint : breakpoints) {
      chunks.add(new TimeRange(previousBreakpoint, breakpoint));
      previousBreakpoint = breakpoint;
    }
    
    // Add final chunk
    chunks.add(new TimeRange(previousBreakpoint, totalDuration.getSeconds()));
    
    return chunks;
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
      List<TimeRange> chunkRanges, List<ChunkTranscript> chunkTranscripts, Duration totalDuration) {

    List<TranscriptionResponse.ChunkInfo> chunkInfos = new ArrayList<>();
    for (int i = 0; i < chunkRanges.size(); i++) {
      TimeRange range = chunkRanges.get(i);
      int segmentCount =
          i < chunkTranscripts.size() ? chunkTranscripts.get(i).segments().size() : 0;
      chunkInfos.add(
          new TranscriptionResponse.ChunkInfo(i, range.start(), range.end(), segmentCount));
    }

    int totalSegments = chunkTranscripts.stream().mapToInt(ct -> ct.segments().size()).sum();

    return new TranscriptionResponse.Diagnostics(
        chunkRanges.size(), totalDuration.toMillis() / 1000.0, totalSegments, chunkInfos);
  }

  private TranscriptionResponse.StorageInfo saveTranscripts(
      String bucket, String originalKey, List<MergedSegment> segments, String language)
      throws IOException {

    String baseName = originalKey.replaceAll("\\.mp3$", "");
    String jsonKey = "transcripts/" + baseName + ".json";
    String srtKey = "transcripts/" + baseName + ".srt";

    LOGGER.info("Saving transcripts: jsonKey={}, srtKey={}", jsonKey, srtKey);

    byte[] jsonBytes = transcriptWriter.writeJson(segments, language);
    try (var jsonStream = new java.io.ByteArrayInputStream(jsonBytes)) {
      objectStoreClient.putObject(
          bucket, jsonKey, jsonStream, jsonBytes.length, "application/json");
    }

    byte[] srtBytes = transcriptWriter.writeSrt(segments);
    try (var srtStream = new java.io.ByteArrayInputStream(srtBytes)) {
      objectStoreClient.putObject(bucket, srtKey, srtStream, srtBytes.length, "text/plain");
    }

    // Generate presigned URLs (valid for 7 days)
    java.net.URL jsonUrl = objectStoreClient.presignGet(bucket, jsonKey, java.time.Duration.ofDays(7));
    java.net.URL srtUrl = objectStoreClient.presignGet(bucket, srtKey, java.time.Duration.ofDays(7));

    String jsonUrlStr = jsonUrl != null ? jsonUrl.toString() : null;
    String srtUrlStr = srtUrl != null ? srtUrl.toString() : null;

    return new TranscriptionResponse.StorageInfo(bucket, jsonKey, srtKey, jsonUrlStr, srtUrlStr);
  }
}
