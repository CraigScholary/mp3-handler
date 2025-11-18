package com.scholary.mp3.handler.chunking;

import com.scholary.mp3.handler.objectstore.ObjectStoreClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Analyzes silence in large audio files using streaming approach.
 *
 * <p>Instead of downloading the entire file, this analyzer:
 * <ul>
 *   <li>Downloads file in chunks (e.g., 20MB at a time)
 *   <li>Analyzes silence in each chunk
 *   <li>Accumulates silence points across entire file
 *   <li>Selects optimal breakpoints for chunking
 * </ul>
 *
 * <p>This allows processing very large files (8+ hours) without loading them into memory.
 */
@Component
public class StreamingSilenceAnalyzer {

  private static final Logger LOGGER = LoggerFactory.getLogger(StreamingSilenceAnalyzer.class);

  // Estimate: MP3 at 128kbps = 16KB/second
  private static final long BYTES_PER_SECOND = 16000;

  // Minimum silence duration to consider as a breakpoint (seconds)
  private static final double MIN_SILENCE_DURATION = 2.0;

  private final ObjectStoreClient objectStoreClient;
  private final FfmpegChunkPlanner ffmpegChunkPlanner;
  private final Path tempDir;
  private final double maxChunkDuration;
  private final double lookbackSeconds;

  public StreamingSilenceAnalyzer(
      ObjectStoreClient objectStoreClient,
      FfmpegChunkPlanner ffmpegChunkPlanner,
      @org.springframework.beans.factory.annotation.Value("${transcription.tempDir}") String tempDir,
      @org.springframework.beans.factory.annotation.Value("${transcription.silence.maxChunkDuration:3600}") double maxChunkDuration,
      @org.springframework.beans.factory.annotation.Value("${transcription.silence.lookbackSeconds:600}") double lookbackSeconds) {
    this.objectStoreClient = objectStoreClient;
    this.ffmpegChunkPlanner = ffmpegChunkPlanner;
    this.tempDir = java.nio.file.Paths.get(tempDir);
    this.maxChunkDuration = maxChunkDuration;
    this.lookbackSeconds = lookbackSeconds;
    
    try {
      java.nio.file.Files.createDirectories(this.tempDir);
    } catch (java.io.IOException e) {
      throw new RuntimeException("Failed to create temp directory: " + tempDir, e);
    }
  }

  /**
   * Find breakpoints using greedy streaming with lookback (returns simple doubles).
   *
   * <p>This is the original method for backward compatibility with V1 API.
   *
   * @param bucket S3 bucket
   * @param key S3 key
   * @param fileSize total file size in bytes
   * @param targetChunkDuration desired chunk duration in seconds (ignored, uses maxChunkDuration)
   * @return list of breakpoints (in seconds from start)
   */
  public List<Double> findBreakpointsGreedy(
      String bucket, String key, long fileSize, double targetChunkDuration) throws IOException {
    
    List<BreakpointWithSilence> breakpointsWithSilence = 
        findBreakpointsGreedyWithSilence(bucket, key, fileSize, targetChunkDuration);
    
    // Extract just the breakpoint values
    List<Double> breakpoints = new ArrayList<>();
    for (BreakpointWithSilence bp : breakpointsWithSilence) {
      breakpoints.add(bp.breakpoint());
    }
    return breakpoints;
  }

  /**
   * Find breakpoints using greedy streaming with lookback.
   *
   * <p>This is a single-pass algorithm that:
   * <ol>
   *   <li>Streams up to maxChunkDuration (e.g., 1 hour)
   *   <li>Finds best silence in lookback window (e.g., last 10 minutes)
   *   <li>Cuts chunk there and yields it for transcription
   *   <li>Continues from that point
   * </ol>
   *
   * <p>This allows parallel processing: Whisper can transcribe chunk N while we're analyzing chunk N+1.
   *
   * <p>Example: maxChunkDuration=3600s (1hr), lookbackSeconds=600s (10min)
   * <ul>
   *   <li>Stream 0-3600s (1 hour)
   *   <li>Find best silence between 3000-3600s (last 10 minutes)
   *   <li>Cut at that silence (e.g., 3450s)
   *   <li>Continue from 3450s
   * </ul>
   *
   * @param bucket S3 bucket
   * @param key S3 key
   * @param fileSize total file size in bytes
   * @param targetChunkDuration desired chunk duration in seconds (ignored, uses maxChunkDuration)
   * @return list of breakpoints with associated silence information
   */
  public List<BreakpointWithSilence> findBreakpointsGreedyWithSilence(
      String bucket, String key, long fileSize, double targetChunkDuration) throws IOException {

    double totalDuration = (double) fileSize / BYTES_PER_SECOND;
    
    LOGGER.info(
        "Starting greedy streaming analysis: fileSize={}MB, totalDuration={}s, maxChunkDuration={}s, lookback={}s",
        fileSize / 1024 / 1024,
        String.format("%.1f", totalDuration),
        String.format("%.1f", maxChunkDuration),
        String.format("%.1f", lookbackSeconds));

    List<BreakpointWithSilence> breakpoints = new ArrayList<>();
    double currentPosition = 0.0;

    while (currentPosition < totalDuration) {
      // Calculate how much to analyze: up to maxChunkDuration (but not past end of file)
      double analyzeUntil = Math.min(currentPosition + maxChunkDuration, totalDuration);
      double analyzeDuration = analyzeUntil - currentPosition;

      LOGGER.info(
          "Analyzing segment: position={}s, duration={}s (max={}s)",
          String.format("%.1f", currentPosition),
          String.format("%.1f", analyzeDuration),
          String.format("%.1f", maxChunkDuration));

      // Download and analyze this segment
      long startByte = (long) (currentPosition * BYTES_PER_SECOND);
      long endByte = Math.min((long) (analyzeUntil * BYTES_PER_SECOND), fileSize - 1);

      Path segmentFile = tempDir.resolve(String.format("greedy_segment_%s.mp3", UUID.randomUUID()));

      try (InputStream stream = objectStoreClient.getObjectRange(bucket, key, startByte, endByte)) {
        Files.copy(stream, segmentFile, StandardCopyOption.REPLACE_EXISTING);
      }

      try {
        // Analyze silence in this segment
        List<SilenceInterval> silences = ffmpegChunkPlanner.analyzeSilence(segmentFile);

        // Adjust timestamps to absolute file position
        List<SilenceInterval> absoluteSilences = new ArrayList<>();
        for (SilenceInterval silence : silences) {
          double absoluteStart = currentPosition + silence.start();
          double absoluteEnd = currentPosition + silence.end();
          double duration = absoluteEnd - absoluteStart;

          if (duration >= MIN_SILENCE_DURATION) {
            absoluteSilences.add(new SilenceInterval(absoluteStart, absoluteEnd));
          }
        }

        LOGGER.info("Found {} significant silences in segment", absoluteSilences.size());

        // Find best breakpoint in lookback window
        // Lookback window is the last N seconds before maxChunkDuration
        double lookbackStart = Math.max(currentPosition, analyzeUntil - lookbackSeconds);
        double lookbackEnd = analyzeUntil;

        LOGGER.debug(
            "Looking for silence in lookback window: {}s - {}s",
            String.format("%.1f", lookbackStart),
            String.format("%.1f", lookbackEnd));

        SilenceInterval bestSilence = findBestSilenceInWindow(
            absoluteSilences, lookbackStart, lookbackEnd);

        if (bestSilence != null) {
          // Use midpoint of best silence as breakpoint
          double breakpoint = (bestSilence.start() + bestSilence.end()) / 2.0;
          breakpoints.add(new BreakpointWithSilence(breakpoint, bestSilence));
          currentPosition = breakpoint;
          
          LOGGER.info(
              "Breakpoint at {}s (silence duration: {}s, in lookback window)",
              String.format("%.2f", breakpoint),
              String.format("%.2f", bestSilence.duration()));
        } else {
          // No silence found in lookback, use max duration
          double breakpoint = Math.min(analyzeUntil, totalDuration);
          if (breakpoint < totalDuration) {
            breakpoints.add(new BreakpointWithSilence(breakpoint));
          }
          currentPosition = breakpoint;
          
          LOGGER.warn("No silence found in lookback window, using max duration at {}s", 
              String.format("%.2f", breakpoint));
        }

      } finally {
        Files.deleteIfExists(segmentFile);
      }

      // Stop if we've reached the end
      if (currentPosition >= totalDuration - 1.0) {
        break;
      }
    }

    LOGGER.info("Greedy analysis complete: found {} breakpoints", breakpoints.size());
    return breakpoints;
  }

  /**
   * Find the longest silence within a time window.
   */
  private SilenceInterval findBestSilenceInWindow(
      List<SilenceInterval> silences, double windowStart, double windowEnd) {
    
    return silences.stream()
        .filter(s -> s.start() >= windowStart && s.end() <= windowEnd)
        .max(Comparator.comparingDouble(SilenceInterval::duration))
        .orElse(null);
  }

  /**
   * Analyze silence across entire file using two-pass approach (DEPRECATED).
   *
   * <p>This method is kept for backward compatibility but is slower than greedy approach.
   *
   * @param bucket S3 bucket
   * @param key S3 key
   * @param fileSize total file size in bytes
   * @param targetChunkCount desired number of chunks
   * @return list of optimal breakpoints (in seconds from start)
   * @deprecated Use {@link #findBreakpointsGreedy(String, String, long, double)} instead
   */
  @Deprecated
  public List<Double> findOptimalBreakpoints(
      String bucket, String key, long fileSize, int targetChunkCount) throws IOException {

    LOGGER.info(
        "Starting streaming silence analysis: fileSize={}MB, targetChunks={}",
        fileSize / 1024 / 1024,
        targetChunkCount);

    // 20MB chunks for two-pass analysis
    final long STREAMING_CHUNK_SIZE = 20 * 1024 * 1024;
    
    List<SilenceInterval> allSilences = new ArrayList<>();
    long currentByte = 0;
    int chunkIndex = 0;
    
    // Calculate total chunks for progress tracking
    int totalAnalysisChunks = (int) Math.ceil((double) fileSize / STREAMING_CHUNK_SIZE);

    // Stream through file in chunks
    while (currentByte < fileSize) {
      long endByte = Math.min(currentByte + STREAMING_CHUNK_SIZE, fileSize - 1);

      LOGGER.info(
          "Analyzing chunk {}/{}: bytes {}-{} ({} MB)",
          chunkIndex + 1,
          totalAnalysisChunks,
          currentByte,
          endByte,
          (endByte - currentByte) / 1024 / 1024);

      // Download this chunk
      Path chunkFile =
          tempDir.resolve(
              String.format("silence_analysis_%d_%s.mp3", chunkIndex, UUID.randomUUID()));

      try (InputStream stream = objectStoreClient.getObjectRange(bucket, key, currentByte, endByte)) {
        Files.copy(stream, chunkFile, StandardCopyOption.REPLACE_EXISTING);
      }

      try {
        // Analyze silence in this chunk
        List<SilenceInterval> chunkSilences = ffmpegChunkPlanner.analyzeSilence(chunkFile);

        // Adjust timestamps to absolute file position
        double chunkStartTime = (double) currentByte / BYTES_PER_SECOND;
        for (SilenceInterval silence : chunkSilences) {
          double absoluteStart = chunkStartTime + silence.start();
          double absoluteEnd = chunkStartTime + silence.end();
          double duration = absoluteEnd - absoluteStart;

          // Only keep significant silences
          if (duration >= MIN_SILENCE_DURATION) {
            allSilences.add(new SilenceInterval(absoluteStart, absoluteEnd));
          }
        }

        LOGGER.info(
            "Found {} significant silences in chunk {} (total: {})",
            chunkSilences.size(),
            chunkIndex,
            allSilences.size());

      } finally {
        Files.deleteIfExists(chunkFile);
      }

      currentByte = endByte + 1;
      chunkIndex++;
    }

    LOGGER.info("Completed streaming analysis: found {} total silence intervals", allSilences.size());

    // Select optimal breakpoints
    return selectOptimalBreakpoints(allSilences, fileSize, targetChunkCount);
  }

  /**
   * Select optimal breakpoints from all detected silences.
   *
   * <p>Strategy:
   * <ul>
   *   <li>Divide file into target number of segments
   *   <li>For each segment, find the longest silence near the ideal breakpoint
   *   <li>Prefer longer silences (better natural breaks)
   *   <li>Ensure breakpoints are reasonably distributed
   * </ul>
   */
  private List<Double> selectOptimalBreakpoints(
      List<SilenceInterval> silences, long fileSize, int targetChunkCount) {

    if (silences.isEmpty()) {
      LOGGER.warn("No silences found, using fixed intervals");
      return createFixedBreakpoints(fileSize, targetChunkCount);
    }

    double totalDuration = (double) fileSize / BYTES_PER_SECOND;
    double idealChunkDuration = totalDuration / targetChunkCount;

    LOGGER.info(
        "Selecting breakpoints: totalDuration={}s, idealChunkDuration={}s",
        totalDuration,
        idealChunkDuration);

    List<Double> breakpoints = new ArrayList<>();

    // For each chunk boundary, find the best silence nearby
    for (int i = 1; i < targetChunkCount; i++) {
      double idealBreakpoint = i * idealChunkDuration;

      // Search window: ±20% of ideal chunk duration
      double searchRadius = idealChunkDuration * 0.2;
      double searchStart = idealBreakpoint - searchRadius;
      double searchEnd = idealBreakpoint + searchRadius;

      // Find longest silence in search window
      SilenceInterval bestSilence =
          silences.stream()
              .filter(s -> s.start() >= searchStart && s.end() <= searchEnd)
              .max(Comparator.comparingDouble(SilenceInterval::duration))
              .orElse(null);

      if (bestSilence != null) {
        // Use midpoint of silence as breakpoint
        double breakpoint = (bestSilence.start() + bestSilence.end()) / 2.0;
        breakpoints.add(breakpoint);
        LOGGER.info(
            "Breakpoint {}: {}s (silence duration: {}s)",
            i,
            String.format("%.2f", breakpoint),
            String.format("%.2f", bestSilence.duration()));
      } else {
        // No silence found, use ideal breakpoint
        breakpoints.add(idealBreakpoint);
        LOGGER.warn("Breakpoint {}: {}s (no silence found, using ideal)", i, idealBreakpoint);
      }
    }

    return breakpoints;
  }

  /**
   * Fallback: create fixed breakpoints if no silences found.
   */
  private List<Double> createFixedBreakpoints(long fileSize, int targetChunkCount) {
    double totalDuration = (double) fileSize / BYTES_PER_SECOND;
    double chunkDuration = totalDuration / targetChunkCount;

    List<Double> breakpoints = new ArrayList<>();
    for (int i = 1; i < targetChunkCount; i++) {
      breakpoints.add(i * chunkDuration);
    }
    return breakpoints;
  }
}
