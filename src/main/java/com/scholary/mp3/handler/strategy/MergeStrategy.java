package com.scholary.mp3.handler.strategy;

import com.scholary.mp3.handler.transcript.ChunkTranscript;
import com.scholary.mp3.handler.transcript.MergedSegment;
import java.util.List;

/**
 * Strategy interface for merging chunk transcripts.
 *
 * <p>Different strategies are used based on the chunking mode:
 *
 * <ul>
 *   <li>Word matching: For overlap-based chunking
 *   <li>Concatenation: For silence-aware chunking
 * </ul>
 */
public interface MergeStrategy {

  /**
   * Merge chunk transcripts into a single coherent transcript.
   *
   * @param chunkTranscripts the transcripts to merge
   * @param chunkPlans the original chunk plans (for overlap information)
   * @return merged transcript
   */
  List<MergedSegment> merge(List<ChunkTranscript> chunkTranscripts, List<ChunkPlan> chunkPlans);

  /**
   * Get the strategy name for logging and debugging.
   *
   * @return strategy name
   */
  String getStrategyName();
}
