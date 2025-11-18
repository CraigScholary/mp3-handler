package com.scholary.mp3.handler.api;

import com.scholary.mp3.handler.transcript.MergedSegment;
import java.util.List;

/**
 * Response for a completed transcription.
 *
 * <p>Contains the merged transcript, diagnostics, and storage locations if saved.
 */
public record TranscriptionResponse(
    String jobId,
    List<MergedSegment> segments,
    String language,
    Diagnostics diagnostics,
    StorageInfo storageInfo) {

  public record Diagnostics(
      int totalChunks, double totalDurationSeconds, int totalSegments, List<ChunkInfo> chunks) {}

  public record ChunkInfo(int index, double start, double end, int segmentCount) {}

  public record StorageInfo(
      String bucket, String jsonKey, String srtKey, String jsonUrl, String srtUrl) {}
}
