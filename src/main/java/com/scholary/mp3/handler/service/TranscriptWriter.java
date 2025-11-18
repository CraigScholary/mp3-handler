package com.scholary.mp3.handler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholary.mp3.handler.api.TranscriptionResponse;
import com.scholary.mp3.handler.objectstore.ObjectStoreClient;
import com.scholary.mp3.handler.transcript.MergedSegment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Writes transcripts in various formats.
 *
 * <p>Supports JSON (machine-readable) and SRT (SubRip subtitle format, human-readable).
 */
@Component
public class TranscriptWriter {

  private final ObjectMapper objectMapper;
  private final ObjectStoreClient objectStoreClient;

  public TranscriptWriter(ObjectMapper objectMapper, ObjectStoreClient objectStoreClient) {
    this.objectMapper = objectMapper;
    this.objectStoreClient = objectStoreClient;
  }

  /**
   * Write transcript as JSON.
   *
   * <p>Format:
   *
   * <pre>
   * {
   *   "language": "en",
   *   "segments": [
   *     {"start": 0.0, "end": 5.2, "text": "Hello world"}
   *   ]
   * }
   * </pre>
   */
  public byte[] writeJson(List<MergedSegment> segments, String language) throws IOException {
    Map<String, Object> transcript = new HashMap<>();
    transcript.put("language", language);
    transcript.put("segments", segments);

    return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(transcript);
  }

  /**
   * Write transcript as SRT (SubRip subtitle format).
   *
   * <p>Format:
   *
   * <pre>
   * 1
   * 00:00:00,000 --> 00:00:05,200
   * Hello world
   *
   * 2
   * 00:00:05,200 --> 00:00:10,300
   * This is a test
   * </pre>
   *
   * <p>SRT is widely supported by video players and subtitle editors, making it useful for manual
   * review and editing.
   */
  public byte[] writeSrt(List<MergedSegment> segments) {
    StringBuilder srt = new StringBuilder();

    for (int i = 0; i < segments.size(); i++) {
      MergedSegment segment = segments.get(i);

      // Sequence number
      srt.append(i + 1).append("\n");

      // Timecodes
      srt.append(formatSrtTime(segment.start()))
          .append(" --> ")
          .append(formatSrtTime(segment.end()))
          .append("\n");

      // Text
      srt.append(segment.text()).append("\n");

      // Blank line between entries
      srt.append("\n");
    }

    return srt.toString().getBytes();
  }

  /**
   * Save transcripts to object store.
   *
   * @param bucket the bucket name
   * @param originalKey the original audio file key
   * @param segments the merged segments
   * @param language the detected language
   * @return storage info with URLs
   */
  public TranscriptionResponse.StorageInfo saveTranscripts(
      String bucket, String originalKey, List<MergedSegment> segments, String language)
      throws IOException {

    // Generate keys
    String baseKey = originalKey.replaceAll("\\.[^.]+$", ""); // Remove extension
    String jsonKey = baseKey + "_transcript.json";
    String srtKey = baseKey + "_transcript.srt";

    // Write JSON
    byte[] jsonBytes = writeJson(segments, language);
    objectStoreClient.putObject(bucket, jsonKey, new ByteArrayInputStream(jsonBytes), "application/json");

    // Write SRT
    byte[] srtBytes = writeSrt(segments);
    objectStoreClient.putObject(bucket, srtKey, new ByteArrayInputStream(srtBytes), "text/plain");

    // Generate presigned URLs (valid for 7 days)
    URL jsonUrl = objectStoreClient.generatePresignedUrl(bucket, jsonKey, Duration.ofDays(7));
    URL srtUrl = objectStoreClient.generatePresignedUrl(bucket, srtKey, Duration.ofDays(7));

    return new TranscriptionResponse.StorageInfo(
        bucket, jsonKey, srtKey, jsonUrl.toString(), srtUrl.toString());
  }

  /**
   * Format a time in seconds as SRT timecode.
   *
   * <p>Format: HH:MM:SS,mmm (hours:minutes:seconds,milliseconds)
   */
  private String formatSrtTime(double seconds) {
    int hours = (int) (seconds / 3600);
    int minutes = (int) ((seconds % 3600) / 60);
    int secs = (int) (seconds % 60);
    int millis = (int) ((seconds - Math.floor(seconds)) * 1000);

    return String.format("%02d:%02d:%02d,%03d", hours, minutes, secs, millis);
  }
}
