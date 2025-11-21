package com.scholary.mp3.handler.api;

import com.scholary.mp3.handler.transcript.MergedSegment;
import java.util.List;

/**
 * Response for a completed transcription.
 *
 * <p>Contains the merged transcript, diagnostics, and storage locations if saved.
 */
public record TranscriptionResponse(
    List<MergedSegment> segments,
    ChunkInfo chunkInfo,
    StorageInfo storageInfo,
    Diagnostics diagnostics) {

  public record ChunkInfo(int totalChunks, int totalSegments) {}

  public record StorageInfo(
      String bucket, String jsonKey, String srtKey, String jsonUrl, String srtUrl) {}
      
  public record Diagnostics(String message) {}
}
