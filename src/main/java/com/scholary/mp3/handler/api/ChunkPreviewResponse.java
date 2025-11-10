package com.scholary.mp3.handler.api;

import java.util.List;

/**
 * Response for chunk preview request.
 *
 * <p>Shows how the file will be split without actually processing it.
 */
public record ChunkPreviewResponse(
    double totalDurationSeconds, int chunkCount, List<ChunkPlan> chunks) {

  public record ChunkPlan(int index, double start, double end, boolean nudged) {}
}
