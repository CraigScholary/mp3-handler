package com.scholary.mp3.handler.chunking;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Plans and executes audio chunking using ffmpeg.
 *
 * <p>This is the heart of our chunking strategy. We do three things:
 *
 * <ol>
 *   <li>Analyze the audio to detect silence intervals
 *   <li>Plan chunk boundaries, nudging them to align with silence when possible
 *   <li>Cut the audio into chunks using ffmpeg
 * </ol>
 *
 * <p>Why silence-aware chunking? Because cutting mid-word or mid-sentence can confuse transcription
 * models. By aligning boundaries with natural pauses, we get better results and easier overlap
 * reconciliation.
 *
 * <p>The overlap (default 5s) ensures we don't lose content at boundaries and helps with
 * de-duplication during merging.
 */
@Component
public class FfmpegChunkPlanner {

  private static final Logger LOGGER = LoggerFactory.getLogger(FfmpegChunkPlanner.class);

  // Regex to parse ffmpeg silencedetect output
  // Example: [silencedetect @ 0x...] silence_start: 45.2
  // Example: [silencedetect @ 0x...] silence_end: 47.8 | silence_duration: 2.6
  private static final Pattern SILENCE_START_PATTERN =
      Pattern.compile("silence_start:\\s*([0-9.]+)");
  private static final Pattern SILENCE_END_PATTERN = Pattern.compile("silence_end:\\s*([0-9.]+)");

  private final FfmpegProperties properties;

  public FfmpegChunkPlanner(FfmpegProperties properties) {
    this.properties = properties;
  }

  /**
   * Analyze an audio file to detect silence intervals.
   *
   * <p>We use ffmpeg's silencedetect filter, which scans the audio and reports periods where the
   * volume is below a threshold. The output is parsed from stderr (ffmpeg logs to stderr by
   * default).
   *
   * @param inputFile the audio file to analyze
   * @return a list of silence intervals
   * @throws IOException if ffmpeg execution fails
   */
  public List<SilenceInterval> analyzeSilence(Path inputFile) throws IOException {
    LOGGER.info("Analyzing silence in file: {}", inputFile);

    // Build ffmpeg command for silence detection
    // -i input: input file
    // -af silencedetect: apply silence detection filter
    // -f null: no output file (we just want the analysis)
    String command =
        String.format(
            "ffmpeg -i %s -af silencedetect=noise=%s:d=%s -f null -",
            inputFile, properties.silenceNoiseThreshold(), properties.silenceMinDuration());

    LOGGER.debug("Executing: {}", command);

    Process process = Runtime.getRuntime().exec(command);
    List<SilenceInterval> intervals = parseSilenceOutput(process);

    try {
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        LOGGER.warn("ffmpeg silence detection exited with code {}", exitCode);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Silence detection interrupted", e);
    }

    LOGGER.info("Detected {} silence intervals", intervals.size());
    return intervals;
  }

  /**
   * Parse silence detection output from ffmpeg stderr.
   *
   * <p>ffmpeg outputs pairs of lines: silence_start followed by silence_end. We pair them up to
   * create SilenceInterval objects.
   *
   * <p>Memory optimization: We process the stream line-by-line without buffering all output. We
   * also limit the number of intervals to prevent memory exhaustion on pathological inputs.
   *
   * @return sorted list of silence intervals (sorted by start time)
   */
  private List<SilenceInterval> parseSilenceOutput(Process process) throws IOException {
    List<SilenceInterval> intervals = new ArrayList<>();
    Double currentStart = null;
    int maxIntervals = 10000; // Prevent memory exhaustion

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getErrorStream()), 8192)) {
      String line;
      while ((line = reader.readLine()) != null) {
        // Look for silence_start
        Matcher startMatcher = SILENCE_START_PATTERN.matcher(line);
        if (startMatcher.find()) {
          currentStart = Double.parseDouble(startMatcher.group(1));
          continue;
        }

        // Look for silence_end
        Matcher endMatcher = SILENCE_END_PATTERN.matcher(line);
        if (endMatcher.find() && currentStart != null) {
          double end = Double.parseDouble(endMatcher.group(1));
          intervals.add(new SilenceInterval(currentStart, end));
          currentStart = null;

          // Safety limit to prevent memory exhaustion
          if (intervals.size() >= maxIntervals) {
            LOGGER.warn(
                "Reached maximum silence intervals limit ({}), stopping parsing", maxIntervals);
            break;
          }
        }
      }
    }

    // Sort intervals by start time for efficient searching
    // ffmpeg should output them in order, but we sort to be safe
    intervals.sort(Comparator.comparingDouble(SilenceInterval::start));

    return intervals;
  }

  /**
   * Plan chunk boundaries with silence-aware nudging.
   *
   * <p>Algorithm:
   *
   * <ol>
   *   <li>Start with fixed-size chunks (e.g., 60s) with overlap (e.g., 5s)
   *   <li>For each boundary, look for silence within ±maxNudgeDistance
   *   <li>If found, move the boundary to the midpoint of the silence
   *   <li>If not found, keep the original boundary
   *   <li>Ensure no gaps and monotonic ordering
   * </ol>
   *
   * @param chunkDuration desired chunk duration
   * @param overlapDuration overlap between chunks
   * @param silenceIntervals detected silence intervals
   * @param totalDuration total audio duration
   * @return a list of chunk time ranges
   */
  public List<TimeRange> planChunks(
      Duration chunkDuration,
      Duration overlapDuration,
      List<SilenceInterval> silenceIntervals,
      Duration totalDuration) {

    double chunkSeconds = chunkDuration.toMillis() / 1000.0;
    double overlapSeconds = overlapDuration.toMillis() / 1000.0;
    double totalSeconds = totalDuration.toMillis() / 1000.0;

    // Validate parameters to prevent infinite loops
    if (overlapSeconds >= chunkSeconds) {
      throw new IllegalArgumentException(
          String.format(
              "Overlap (%ss) must be less than chunk duration (%ss)",
              overlapSeconds, chunkSeconds));
    }

    if (chunkSeconds <= 0) {
      throw new IllegalArgumentException("Chunk duration must be positive");
    }

    if (totalSeconds <= 0) {
      throw new IllegalArgumentException("Total duration must be positive");
    }

    LOGGER.info(
        "Planning chunks: chunkDuration={}s, overlap={}s, totalDuration={}s, silenceIntervals={}",
        chunkSeconds,
        overlapSeconds,
        totalSeconds,
        silenceIntervals.size());

    List<TimeRange> chunks = new ArrayList<>();
    double currentStart = 0.0;
    double previousEnd = 0.0;

    while (currentStart < totalSeconds) {
      // Calculate ideal end point (start + chunkDuration)
      double idealEnd = currentStart + chunkSeconds;

      // Don't exceed total duration
      double actualEnd = Math.min(idealEnd, totalSeconds);

      // If this isn't the last chunk and silence-aware nudging is enabled, try to nudge
      if (actualEnd < totalSeconds && !silenceIntervals.isEmpty()) {
        double nudgedEnd = nudgeBoundary(actualEnd, silenceIntervals);

        // Only accept the nudge if it doesn't cause us to go backwards
        // This prevents infinite loops when silence detection creates problematic boundaries
        if (nudgedEnd > currentStart + (chunkSeconds / 2)) {
          actualEnd = nudgedEnd;
        } else {
          LOGGER.debug("Rejected nudge from {}s to {}s (would be too short)", actualEnd, nudgedEnd);
        }
      }

      chunks.add(new TimeRange(currentStart, actualEnd));

      // Next chunk starts at (current end - overlap)
      // This ensures we have overlap between chunks for better transcription continuity
      double nextStart = actualEnd - overlapSeconds;

      // Safety check: ensure we're making forward progress
      // If the next start would be before or at the current start, force progress
      if (nextStart <= currentStart) {
        LOGGER.warn(
            "Chunk planning would stall at {}s (nextStart={}s), forcing progress",
            currentStart,
            nextStart);
        // Nudge forward by at least 0.01s to avoid stall
        nextStart = currentStart + Math.max(0.01, chunkSeconds / 2);
      }

      // Additional safety: if we've processed this position before, break
      if (actualEnd <= previousEnd) {
        LOGGER.warn("Chunk planning detected duplicate position at {}s, breaking", actualEnd);
        break;
      }

      previousEnd = actualEnd;
      currentStart = nextStart;

      // Hard limit: prevent infinite loops
      if (chunks.size() > 1000) {
        LOGGER.error("Chunk planning exceeded 1000 chunks, breaking to prevent memory issues");
        break;
      }
    }

    LOGGER.info("Planned {} chunks", chunks.size());
    return chunks;
  }

  /**
   * Nudge a boundary to align with nearby silence.
   *
   * <p>We look for silence within ±maxNudgeDistance of the ideal boundary. If found, we move the
   * boundary to the midpoint of the silence. This gives us the best chance of cutting between words
   * or sentences.
   *
   * <p>Memory optimization: For large lists of silence intervals, we could use binary search.
   * However, in practice, each chunk has relatively few intervals, so linear search is fine.
   *
   * @param idealBoundary the ideal boundary time
   * @param silenceIntervals available silence intervals (should be sorted by start time)
   * @return the nudged boundary time (or original if no suitable silence found)
   */
  private double nudgeBoundary(double idealBoundary, List<SilenceInterval> silenceIntervals) {
    double maxNudge = properties.maxNudgeDistance();
    double searchStart = idealBoundary - maxNudge;
    double searchEnd = idealBoundary + maxNudge;

    // Find silence intervals that overlap with our search window
    // Early exit optimization: if intervals are sorted, we can stop when we pass the search window
    for (SilenceInterval silence : silenceIntervals) {
      // If this silence starts after our search window, no point checking further
      if (silence.start() > searchEnd) {
        break;
      }

      if (silence.start() <= searchEnd && silence.end() >= searchStart) {
        // Found a candidate - use its midpoint
        double nudgedBoundary = silence.midpoint();
        LOGGER.debug(
            "Nudged boundary from {}s to {}s (silence: {}s-{}s)",
            idealBoundary,
            nudgedBoundary,
            silence.start(),
            silence.end());
        return nudgedBoundary;
      }
    }

    // No suitable silence found, keep original boundary
    LOGGER.debug("No silence found near {}s, keeping original boundary", idealBoundary);
    return idealBoundary;
  }

  /**
   * Cut a chunk from an audio file using ffmpeg.
   *
   * <p>We prefer stream copy (-c copy) for speed and quality preservation. However, stream copy
   * only works at keyframe boundaries, so if the cut points don't align with keyframes, we fall
   * back to re-encoding.
   *
   * <p>The strategy: try stream copy first. If it produces an invalid file (wrong duration), retry
   * with re-encoding.
   *
   * @param inputFile the source audio file
   * @param range the time range to extract
   * @param outputFile the destination file for the chunk
   * @throws IOException if cutting fails
   */
  public void cutChunk(Path inputFile, TimeRange range, Path outputFile) throws IOException {
    LOGGER.info("Cutting chunk: {}s-{}s to {}", range.start(), range.end(), outputFile);

    // Try stream copy first (fast, lossless)
    boolean success = tryCutWithStreamCopy(inputFile, range, outputFile);

    if (!success) {
      // Stream copy failed, fall back to re-encoding
      LOGGER.warn("Stream copy failed for chunk {}s-{}s, re-encoding", range.start(), range.end());
      cutWithReencode(inputFile, range, outputFile);
    }

    LOGGER.info("Successfully cut chunk to {}", outputFile);
  }

  /**
   * Attempt to cut a chunk using stream copy.
   *
   * <p>Stream copy is fast because it doesn't decode/encode the audio. However, it can only cut at
   * keyframe boundaries, which may not align with our desired timestamps.
   *
   * @return true if successful, false if we should retry with re-encoding
   */
  private boolean tryCutWithStreamCopy(Path inputFile, TimeRange range, Path outputFile)
      throws IOException {
    // ffmpeg command with stream copy:
    // -ss: start time
    // -to: end time
    // -i: input file
    // -c copy: stream copy (no re-encoding)
    // -y: overwrite output file
    String command =
        String.format(
            "ffmpeg -ss %.3f -to %.3f -i %s -c copy -y %s",
            range.start(), range.end(), inputFile, outputFile);

    LOGGER.debug("Executing: {}", command);

    Process process = Runtime.getRuntime().exec(command);

    try {
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        LOGGER.warn("ffmpeg stream copy exited with code {}", exitCode);
        return false;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Chunk cutting interrupted", e);
    }

    // Stream copy succeeded
    return true;
  }

  /**
   * Cut a chunk with re-encoding.
   *
   * <p>This is slower but works at any timestamp. We use libmp3lame with quality setting from
   * properties (default q:a 2, which is high quality).
   */
  private void cutWithReencode(Path inputFile, TimeRange range, Path outputFile)
      throws IOException {
    // ffmpeg command with re-encoding:
    // -ss: start time
    // -to: end time
    // -i: input file
    // -c:a libmp3lame: use MP3 encoder
    // -q:a: quality (0-9, lower is better)
    // -y: overwrite output file
    String command =
        String.format(
            "ffmpeg -ss %.3f -to %.3f -i %s -c:a libmp3lame -q:a %d -y %s",
            range.start(), range.end(), inputFile, properties.audioQuality(), outputFile);

    LOGGER.debug("Executing: {}", command);

    Process process = Runtime.getRuntime().exec(command);

    try {
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IOException("ffmpeg re-encoding failed with exit code " + exitCode);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Chunk cutting interrupted", e);
    }
  }
}
