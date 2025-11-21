package com.scholary.mp3.handler.service;

import com.scholary.mp3.handler.api.TranscriptionRequest;
import com.scholary.mp3.handler.api.TranscriptionResponse;
import com.scholary.mp3.handler.cache.ChunkCache;
import com.scholary.mp3.handler.objectstore.ObjectStoreClient;
import com.scholary.mp3.handler.objectstore.ObjectStoreClient.ObjectMetadata;
import com.scholary.mp3.handler.transcript.ChunkTranscript;
import com.scholary.mp3.handler.transcript.MergedSegment;
import com.scholary.mp3.handler.transcript.WordMatchingMerger;
import com.scholary.mp3.handler.whisper.TranscriptSegment;
import com.scholary.mp3.handler.whisper.WhisperResponse;
import com.scholary.mp3.handler.whisper.WhisperService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * Simplified transcription service using dynamic overlap chunking.
 * 
 * <p>Streams audio from object store, processes in chunks with intelligent overlap,
 * and merges transcripts using word matching.
 */
@Service
public class TranscriptionService {

  private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptionService.class);
  private static final int MIN_WORDS_FOR_MERGE = 5;

  private final ObjectStoreClient objectStoreClient;
  private final WhisperService whisperService;
  private final WordMatchingMerger wordMatchingMerger;
  private final TranscriptWriter transcriptWriter;
  private final ChunkCache chunkCache;
  private final Path tempDir;
  private final int maxFileDurationHours;

  public TranscriptionService(
      ObjectStoreClient objectStoreClient,
      WhisperService whisperService,
      WordMatchingMerger wordMatchingMerger,
      TranscriptWriter transcriptWriter,
      ChunkCache chunkCache,
      @Value("${transcription.tempDir}") String tempDir,
      @Value("${transcription.chunking.maxFileDurationHours}") int maxFileDurationHours) {

    this.objectStoreClient = objectStoreClient;
    this.whisperService = whisperService;
    this.wordMatchingMerger = wordMatchingMerger;
    this.transcriptWriter = transcriptWriter;
    this.chunkCache = chunkCache;
    this.tempDir = Paths.get(tempDir);
    this.maxFileDurationHours = maxFileDurationHours;

    try {
      Files.createDirectories(this.tempDir);
    } catch (IOException e) {
      throw new RuntimeException("Failed to create temp directory: " + tempDir, e);
    }
  }

  /**
   * Transcribe audio file using streaming with dynamic overlap.
   */
  public TranscriptionResponse transcribe(TranscriptionRequest request) throws IOException {
    String correlationId = UUID.randomUUID().toString();
    MDC.put("correlationId", correlationId);

    try {
      LOGGER.info(
          "Starting transcription: bucket={}, key={}, maxChunkSeconds={}, minOverlap={}",
          request.bucket(),
          request.key(),
          request.chunkSeconds(),
          request.minOverlapSeconds());

      // Get file metadata
      ObjectMetadata metadata =
          objectStoreClient.getObjectMetadata(request.bucket(), request.key());
      LOGGER.info("File size: {} MB", metadata.contentLength() / 1024 / 1024);

      // Get actual duration using ffprobe
      Duration actualDuration = getActualDuration(request.bucket(), request.key());
      LOGGER.info("Actual duration: {} hours", actualDuration.toHours());

      validateDuration(actualDuration);

      // Process chunks with dynamic overlap
      List<ChunkTranscript> chunkTranscripts = new ArrayList<>();
      double currentPosition = 0.0;
      int chunkIndex = 0;
      double totalDuration = actualDuration.getSeconds();

      while (currentPosition < totalDuration) {
        // Calculate chunk duration (max 1 hour or remaining time)
        double chunkDuration =
            Math.min(request.chunkSeconds(), totalDuration - currentPosition);

        LOGGER.info(
            "Processing chunk {}: {}-{} seconds",
            chunkIndex,
            currentPosition,
            currentPosition + chunkDuration);

        // Check cache first
        String cacheKey =
            String.format("%s:%s:%.2f:%.2f", request.bucket(), request.key(), currentPosition, chunkDuration);
        
        ChunkTranscript transcript;
        var cached = chunkCache.get(cacheKey);
        if (cached.isPresent()) {
          LOGGER.info("Using cached transcript for chunk {}", chunkIndex);
          transcript = cached.get();
        } else {
          LOGGER.info("Cache miss, processing chunk {}", chunkIndex);
          transcript = processChunk(
              request.bucket(),
              request.key(),
              currentPosition,
              chunkDuration,
              chunkIndex);
          chunkCache.put(cacheKey, transcript);
        }

        chunkTranscripts.add(transcript);

        // Calculate next chunk start position with dynamic overlap
        if (currentPosition + chunkDuration < totalDuration) {
          double overlapStart = calculateDynamicOverlap(transcript, request.minOverlapSeconds());
          currentPosition = currentPosition + chunkDuration - overlapStart;
          LOGGER.info("Next chunk will start at {} (overlap: {} seconds)", currentPosition, overlapStart);
        } else {
          break;
        }

        chunkIndex++;
      }

      LOGGER.info("Processed {} chunks, merging transcripts", chunkTranscripts.size());

      // Merge all chunks
      List<MergedSegment> mergedSegments = mergeChunks(chunkTranscripts);

      // Save transcripts if requested
      TranscriptionResponse.StorageInfo storageInfo = null;
      if (request.save()) {
        String language = chunkTranscripts.isEmpty() ? "en" : chunkTranscripts.get(0).language();
        storageInfo = saveTranscripts(request.bucket(), request.key(), mergedSegments, language);
      }

      return new TranscriptionResponse(
          mergedSegments,
          new TranscriptionResponse.ChunkInfo(chunkTranscripts.size(), 0),
          storageInfo,
          null);

    } finally {
      MDC.remove("correlationId");
    }
  }

  /**
   * Calculate dynamic overlap based on transcript content.
   * Returns the number of seconds to overlap from the end of the chunk.
   */
  private double calculateDynamicOverlap(ChunkTranscript transcript, int minOverlapSeconds) {
    List<TranscriptSegment> segments = transcript.segments();
    if (segments.isEmpty()) {
      return minOverlapSeconds;
    }

    double chunkEnd = transcript.endTime();
    double overlapStart = chunkEnd - minOverlapSeconds;

    // Find segments in the overlap window
    List<TranscriptSegment> overlapSegments = new ArrayList<>();
    for (TranscriptSegment segment : segments) {
      if (segment.end() > overlapStart) {
        overlapSegments.add(segment);
      }
    }

    // Count words in overlap
    int wordCount = overlapSegments.stream()
        .mapToInt(s -> s.text().split("\\s+").length)
        .sum();

    // If we have enough words, use this overlap
    if (wordCount >= MIN_WORDS_FOR_MERGE) {
      double actualOverlap = chunkEnd - overlapSegments.get(0).start();
      return actualOverlap;
    }

    // Double the overlap and try again (up to 2 minutes max)
    int extendedOverlap = Math.min(minOverlapSeconds * 2, 120);
    if (extendedOverlap > minOverlapSeconds) {
      LOGGER.info(
          "Insufficient words ({}) in {}s overlap, extending to {}s",
          wordCount,
          minOverlapSeconds,
          extendedOverlap);
      return extendedOverlap;
    }

    return minOverlapSeconds;
  }

  /**
   * Process a single chunk: extract from object store and transcribe.
   */
  private ChunkTranscript processChunk(
      String bucket, String key, double startTime, double duration, int chunkIndex)
      throws IOException {

    Path chunkFile = tempDir.resolve(String.format("chunk_%d_%s.mp3", chunkIndex, UUID.randomUUID()));

    try {
      // Get presigned URL for ffmpeg
      String presignedUrl = objectStoreClient.presignGet(bucket, key, Duration.ofMinutes(30)).toString();

      // Extract chunk using ffmpeg
      ProcessBuilder pb =
          new ProcessBuilder(
              "ffmpeg",
              "-ss", String.format("%.3f", startTime),
              "-i", presignedUrl,
              "-t", String.format("%.3f", duration),
              "-c", "copy",
              "-y",
              chunkFile.toString());
      pb.redirectErrorStream(true);

      Process process = pb.start();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        String error = new String(process.getInputStream().readAllBytes());
        throw new IOException("ffmpeg extraction failed: " + error);
      }

      // Transcribe chunk
      WhisperResponse response = whisperService.transcribe(chunkFile, duration, chunkIndex);

      return new ChunkTranscript(
          chunkIndex,
          startTime,
          startTime + duration,
          response.segments(),
          response.language());

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Chunk processing interrupted", e);
    } finally {
      Files.deleteIfExists(chunkFile);
    }
  }

  /**
   * Merge all chunk transcripts using word matching.
   */
  private List<MergedSegment> mergeChunks(List<ChunkTranscript> chunks) {
    if (chunks.isEmpty()) {
      return List.of();
    }
    if (chunks.size() == 1) {
      return convertToMergedSegments(chunks.get(0));
    }

    List<MergedSegment> result = new ArrayList<>(convertToMergedSegments(chunks.get(0)));

    for (int i = 1; i < chunks.size(); i++) {
      ChunkTranscript prevChunk = chunks.get(i - 1);
      ChunkTranscript currentChunk = chunks.get(i);

      List<MergedSegment> merged = wordMatchingMerger.merge(prevChunk, currentChunk);
      
      // Remove overlapping segments from result and add merged
      result = removeOverlap(result, prevChunk.endTime());
      result.addAll(merged);
    }

    return result;
  }

  private List<MergedSegment> convertToMergedSegments(ChunkTranscript chunk) {
    return chunk.segments().stream()
        .map(s -> new MergedSegment(
            chunk.startTime() + s.start(), 
            chunk.startTime() + s.end(), 
            s.text()))
        .toList();
  }

  private List<MergedSegment> removeOverlap(List<MergedSegment> segments, double overlapStart) {
    return segments.stream()
        .filter(s -> s.start() < overlapStart)
        .toList();
  }

  /**
   * Get actual audio duration using ffprobe.
   */
  private Duration getActualDuration(String bucket, String key) throws IOException {
    String presignedUrl = objectStoreClient.presignGet(bucket, key, Duration.ofMinutes(5)).toString();
    
    LOGGER.debug("Getting duration with ffprobe: url={}", presignedUrl);

    ProcessBuilder pb =
        new ProcessBuilder(
            "ffprobe",
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            presignedUrl);
    pb.redirectErrorStream(true);

    try {
      Process process = pb.start();
      String output = new String(process.getInputStream().readAllBytes()).trim();
      int exitCode = process.waitFor();

      if (exitCode != 0) {
        LOGGER.error("ffprobe failed: url={}, output={}", presignedUrl, output);
        throw new IOException("ffprobe failed with exit code: " + exitCode + ", output: " + output);
      }

      double durationSeconds = Double.parseDouble(output);
      return Duration.ofSeconds((long) durationSeconds);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("ffprobe interrupted", e);
    } catch (NumberFormatException e) {
      throw new IOException("Failed to parse duration from ffprobe output", e);
    }
  }

  private void validateDuration(Duration duration) {
    long hours = duration.toHours();
    if (hours > maxFileDurationHours) {
      throw new IllegalArgumentException(
          String.format(
              "File duration (%d hours) exceeds maximum allowed (%d hours)",
              hours, maxFileDurationHours));
    }
  }

  private TranscriptionResponse.StorageInfo saveTranscripts(
      String bucket, String key, List<MergedSegment> segments, String language)
      throws IOException {

    String jsonKey = key.replaceAll("\\.[^.]+$", ".json");
    String srtKey = key.replaceAll("\\.[^.]+$", ".srt");

    // Write transcripts
    byte[] jsonBytes = transcriptWriter.writeJson(segments, language);
    byte[] srtBytes = transcriptWriter.writeSrt(segments);

    // Upload to object store
    objectStoreClient.putObject(
        bucket, jsonKey, new java.io.ByteArrayInputStream(jsonBytes), jsonBytes.length, "application/json");
    objectStoreClient.putObject(
        bucket, srtKey, new java.io.ByteArrayInputStream(srtBytes), srtBytes.length, "text/plain");

    // Generate presigned URLs
    java.net.URL jsonUrl = objectStoreClient.presignGet(bucket, jsonKey, Duration.ofDays(7));
    java.net.URL srtUrl = objectStoreClient.presignGet(bucket, srtKey, Duration.ofDays(7));

    return new TranscriptionResponse.StorageInfo(
        bucket, jsonKey, srtKey, jsonUrl.toString(), srtUrl.toString());
  }
}
